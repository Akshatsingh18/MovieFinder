package com.moviefinder.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.concurrent.Executor;

@Service
public class CatalogSyncService {
    private static final String TMDB="https://api.themoviedb.org/3";
    private static final List<String> SERVICES=List.of("Netflix","Amazon Prime Video","JioHotstar");
    private final CatalogRepository repository;
    private final Executor syncExecutor;
    private final RestClient tmdb,omdb=RestClient.create();
    private final String tmdbKey,omdbKey,region;
    private final boolean v3;
    private final int syncPages;
    private final long syncOpenCooldownMs;
    private final AtomicBoolean running=new AtomicBoolean(false),ratingRefreshing=new AtomicBoolean(false);
    private final Object jobLock=new Object();
    private volatile Instant omdbRateLimitedUntil=Instant.MIN;
    private volatile String message="Waiting for first background sync";
    private final Map<String,Map<Integer,String>> genres=new ConcurrentHashMap<>();

    public CatalogSyncService(CatalogRepository repository, @Qualifier("catalogSyncExecutor") Executor syncExecutor,
      @Value("${TMDB_API_KEY:}") String tmdbKey,@Value("${OMDB_API_KEY:}") String omdbKey,
      @Value("${app.region:IN}") String region,@Value("${app.sync-pages:5}") int syncPages,
      @Value("${app.sync-open-cooldown-ms:21600000}") long syncOpenCooldownMs){
        if(tmdbKey.isBlank())throw new IllegalStateException("Set TMDB_API_KEY before starting the backend.");
        this.repository=repository;this.syncExecutor=syncExecutor;this.tmdbKey=tmdbKey;this.omdbKey=omdbKey;this.region=region;this.syncPages=Math.max(1,Math.min(syncPages,50));
        this.syncOpenCooldownMs=syncOpenCooldownMs;
        this.v3=tmdbKey.matches("(?i)[0-9a-f]{32}");
        RestClient.Builder b=RestClient.builder().baseUrl(TMDB);if(!v3)b.defaultHeader("Authorization","Bearer "+tmdbKey);this.tmdb=b.build();
    }

    @Scheduled(initialDelay=10000,fixedDelayString="${app.sync-interval-ms:86400000}")
    public void scheduledSync(){requestSync();}

    public boolean requestSyncIfStale(){
        if(running.get()||ratingRefreshing.get())return false;
        Map<String,Object> state=repository.syncState();
        String status=String.valueOf(state.getOrDefault("status","pending"));
        if(status.equals("running"))return false;
        Object finished=state.get("finishedAt");
        Instant last=finished instanceof java.sql.Timestamp ts?ts.toInstant():finished instanceof java.time.OffsetDateTime odt?odt.toInstant():null;
        if(last!=null&&Duration.between(last,Instant.now()).toMillis()<syncOpenCooldownMs)return false;
        return startCatalogSync();
    }

    public void requestSync(){
        startCatalogSync();
    }

    private boolean startCatalogSync(){
        synchronized(jobLock){
            if(running.get()||ratingRefreshing.get())return false;
            running.set(true);
            try{syncExecutor.execute(this::syncCatalog);return true;}
            catch(RuntimeException e){running.set(false);throw e;}
        }
    }

    public boolean requestRatingRefresh(){
        synchronized(jobLock){
            if(running.get()||ratingRefreshing.get())return false;
            ratingRefreshing.set(true);
            try{syncExecutor.execute(this::refreshRatings);return true;}
            catch(RuntimeException e){ratingRefreshing.set(false);throw e;}
        }
    }

    private void syncCatalog(){
        Instant start=Instant.now();int seen=0;message="Catalog refresh is running";
        repository.setSyncState("running",java.sql.Timestamp.from(start),null,0,message);
        try{
            Map<String,List<String>> providers=providerIds();
            for(String service:SERVICES){
                List<String> ids=providers.getOrDefault(service,List.of());if(ids.isEmpty())continue;
                for(String media:List.of("movie","tv")){
                    for(String providerId:ids){
                        for(int page=1;page<=syncPages;page++){
                            Map<String,Object> data=discover(media,providerId,page);
                            List<Map<String,Object>> hits=(List<Map<String,Object>>)data.getOrDefault("results",List.of());
                            List<Map<String,Object>> batch=hits.stream().map(hit->toItem(media,hit,service)).toList();
                            repository.upsertBatch(batch,region,service);seen+=batch.size();
                            if(seen>0&&seen%100<batch.size()){
                                message="Imported "+seen+" titles; checking streaming availability…";
                                repository.setSyncState("running",java.sql.Timestamp.from(start),null,seen,message);
                            }
                            int total=((Number)data.getOrDefault("total_pages",1)).intValue();if(page>=total)break;
                        }
                    }
                }
                repository.removeStalePlatformRows(service,start);
            }
            message="Catalog sync complete";
            repository.setSyncState("complete",java.sql.Timestamp.from(start),java.sql.Timestamp.from(Instant.now()),seen,message);
        }catch(Exception e){message="Catalog sync failed: "+e.getClass().getSimpleName();repository.setSyncState("failed",java.sql.Timestamp.from(start),java.sql.Timestamp.from(Instant.now()),seen,message);}
        finally{running.set(false);}
    }

    public boolean isSyncing(){return running.get();}
    public boolean isRatingRefreshing(){return ratingRefreshing.get();}
    public String message(){return message;}

    public Map<String,Object> titleWatchLink(String catalogId){
        int separator=catalogId.lastIndexOf('-');
        if(separator<1)throw new IllegalArgumentException("Invalid catalog title id");
        String tmdbId=catalogId.substring(0,separator),media=catalogId.substring(separator+1);
        if(!tmdbId.matches("\\d+")||!List.of("movie","tv").contains(media))
            throw new IllegalArgumentException("Invalid catalog title id");
        Map<String,Object> data=tmdb.get().uri(path("/{media}/{id}/watch/providers"),vars(media,tmdbId)).retrieve().body(Map.class);
        if(data==null)return Map.of("url","","source","none");
        Map<String,Object> results=(Map<String,Object>)data.getOrDefault("results",Map.of());
        Map<String,Object> country=(Map<String,Object>)results.getOrDefault(region,Map.of());
        String link=Objects.toString(country.get("link"),"");
        return Map.of("url",link,"source",link.isBlank()?"none":"tmdb");
    }

    private Map<String,Object> discover(String media,String provider,int page){
        return tmdb.get().uri(u->{u.path("/discover/"+media).queryParam("watch_region",region).queryParam("with_watch_providers",provider)
            .queryParam("with_watch_monetization_types","flatrate").queryParam("sort_by",media.equals("movie")?"primary_release_date.desc":"first_air_date.desc")
            .queryParam(media.equals("movie")?"primary_release_date.lte":"first_air_date.lte",LocalDate.now().toString()).queryParam("page",page);
            if(v3)u.queryParam("api_key",tmdbKey);return u.build();}).retrieve().body(Map.class);
    }
    private Map<String,List<String>> providerIds(){
        Map<String,Object> data=tmdb.get().uri(path("/watch/providers/movie?watch_region={region}&language=en-US"),vars(region)).retrieve().body(Map.class);
        Map<String,List<String>> result=new LinkedHashMap<>();for(String s:SERVICES)result.put(s,new ArrayList<>());
        if(data!=null)for(Map<String,Object> p:(List<Map<String,Object>>)data.getOrDefault("results",List.of())){
            String name=String.valueOf(p.getOrDefault("provider_name","")).toLowerCase(Locale.ROOT);
            String service=name.contains("netflix")?"Netflix":name.contains("amazon")&&name.contains("prime video")?"Amazon Prime Video":name.contains("hotstar")?"JioHotstar":null;
            if(service!=null)result.get(service).add(String.valueOf(p.get("provider_id")));
        }return result;
    }
    private Map<String,Object> toItem(String media,Map<String,Object> hit,String platform){
        String id=String.valueOf(hit.get("id")),itemId=id+"-"+media;
        String date=String.valueOf(hit.getOrDefault("release_date",hit.getOrDefault("first_air_date","")));
        List<String> tags=new ArrayList<>();Map<Integer,String> map=genres.computeIfAbsent(media,this::loadGenres);
        for(Number n:(List<Number>)hit.getOrDefault("genre_ids",List.of()))if(map.containsKey(n.intValue()))tags.add(map.get(n.intValue()));
        String title=String.valueOf(hit.getOrDefault("title",hit.get("name")));
        Object originCountry=hit.get("origin_country"),originalLanguage=hit.get("original_language");
        boolean koreanLanguage="ko".equalsIgnoreCase(Objects.toString(originalLanguage,""));
        boolean koreanCountry=originCountry instanceof Collection<?> countries&&countries.stream().anyMatch(country->"KR".equalsIgnoreCase(String.valueOf(country)));
        if((koreanLanguage||koreanCountry)&&!tags.contains("Korean"))tags.add("Korean");
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",itemId);row.put("type",media);row.put("name",title);
        row.put("year",date.length()>=4?date.substring(0,4):"");row.put("genres",tags);row.put("platforms",List.of(platform));
        // Rating lookups stay in the explicit background refresh job so catalog import does not block on OMDb.
        row.put("imdbId","");row.put("imdbRating","N/A");row.put("imdbVotes",null);row.put("imdbRatingCheckedAt",null);row.put("popularity",hit.getOrDefault("popularity",0));row.put("poster",hit.get("poster_path")==null?"":"https://image.tmdb.org/t/p/w342"+hit.get("poster_path"));
        row.put("overview",hit.getOrDefault("overview",""));return row;
    }
    private Map<Integer,String> loadGenres(String media){Map<String,Object> data=tmdb.get().uri(path("/genre/{media}/list?language=en"),vars(media)).retrieve().body(Map.class);Map<Integer,String> r=new HashMap<>();if(data!=null)for(Map<String,Object> g:(List<Map<String,Object>>)data.getOrDefault("genres",List.of()))r.put(((Number)g.get("id")).intValue(),String.valueOf(g.get("name")));return r;}
    private String externalId(String media,String id){try{Map<String,Object> d=tmdb.get().uri(path("/{media}/{id}/external_ids"),vars(media,id)).retrieve().body(Map.class);Object ext=d==null?null:d.get("imdb_id");return ext==null?"":String.valueOf(ext);}catch(Exception e){return "";}}
    private record RatingResult(String imdbId,Double rating,Long votes,boolean checked,boolean rateLimited,boolean failed){}
    private record OmdbResponse(Map<String,Object> data,boolean rateLimited,boolean failed){}

    private RatingResult lookupRating(String imdbId,String title,String date,String media){
        if(omdbKey.isBlank())return new RatingResult(imdbId,null,null,false,false,false);
        if(Instant.now().isBefore(omdbRateLimitedUntil)||Boolean.TRUE.equals(repository.omdbDailyUsage().get("limitReached")))return new RatingResult(imdbId,null,null,false,true,false);
        boolean attempted=false;
        if(!imdbId.isBlank()){
            OmdbResponse response=omdbRequest("https://www.omdbapi.com/?i={id}&apikey={key}",imdbId,omdbKey);
            if(response.rateLimited()){repository.markOmdbLimitReached();return new RatingResult(imdbId,null,null,false,true,false);}
            if(response.failed())return new RatingResult(imdbId,null,null,false,false,true);
            Map<String,Object> data=response.data();
            if(data!=null&&"True".equalsIgnoreCase(String.valueOf(data.get("Response"))))
                return ratingFrom(data,imdbId,true);
            attempted=true;
        }
        if(date.length()<4)return new RatingResult(imdbId,null,null,attempted,false,false);
        String kind=media.equals("tv")?"series":"movie",year=date.substring(0,4);
        OmdbResponse response=omdbRequest("https://www.omdbapi.com/?t={title}&y={year}&type={type}&apikey={key}",title,year,kind,omdbKey);
        if(response.rateLimited()){repository.markOmdbLimitReached();return new RatingResult(imdbId,null,null,false,true,false);}
        if(response.failed())return new RatingResult(imdbId,null,null,false,false,true);
        Map<String,Object> data=response.data();
        if(data!=null&&"True".equalsIgnoreCase(String.valueOf(data.get("Response")))
            &&sameTitle(title,String.valueOf(data.getOrDefault("Title","")))
            &&sameYear(year,String.valueOf(data.getOrDefault("Year",""))))
            return ratingFrom(data,imdbId,true);
        return new RatingResult(imdbId,null,null,true,false,false);
    }

    private RatingResult ratingFrom(Map<String,Object> data,String fallbackId,boolean checked){
        String resultId=String.valueOf(data.getOrDefault("imdbID",fallbackId));
        if(resultId.equals("null")||resultId.isBlank())resultId=fallbackId;
        Double rating=null;
        try{rating=Double.valueOf(String.valueOf(data.get("imdbRating")));}catch(Exception ignored){}
        Long votes=null;
        try{votes=Long.parseLong(String.valueOf(data.getOrDefault("imdbVotes","")).replace(",",""));}catch(Exception ignored){}
        return new RatingResult(resultId,rating,votes,checked,false,false);
    }

    private OmdbResponse omdbRequest(String uri,Object... variables){
        repository.recordOmdbRequest();
        try{
            Map<String,Object> data=omdb.get().uri(uri,variables).retrieve().body(Map.class);
            String error=data==null?"":String.valueOf(data.getOrDefault("Error",""));
            String normalizedError=error.toLowerCase(Locale.ROOT);
            if(normalizedError.contains("limit")){repository.markOmdbLimitReached();return new OmdbResponse(data,true,false);}
            if(normalizedError.contains("api key")||normalizedError.contains("unauthorized"))return new OmdbResponse(data,false,true);
            return new OmdbResponse(data,false,data==null);
        }catch(RestClientResponseException e){
            String responseText=Objects.toString(e.getResponseBodyAsString(),"").toLowerCase(Locale.ROOT);
            boolean rateLimited=e.getStatusCode().value()==429||responseText.contains("limit");
            if(rateLimited)repository.markOmdbLimitReached();
            return new OmdbResponse(null,rateLimited,true);
        }catch(Exception e){return new OmdbResponse(null,false,true);}
    }

    private void refreshRatings(){
        Instant start=Instant.now();int checked=0,updated=0;
        String status="failed",message="Rating refresh failed";
        try{
            repository.setRatingRefreshState("running",java.sql.Timestamp.from(start),null,0,0,"Checking IMDb ratings in the background");
            if(omdbKey.isBlank()){
                message="OMDB_API_KEY is missing from the backend environment.";
            }else{
                status="complete";
                List<Map<String,Object>> candidates=repository.ratingCandidates(region);
                for(Map<String,Object> item:candidates){
                    String id=String.valueOf(item.get("id")),media=String.valueOf(item.get("mediaType"));
                    String title=String.valueOf(item.get("title"));
                    Object yearValue=item.get("releaseYear");String date=yearValue==null?"":String.valueOf(yearValue);
                    String imdbId=Objects.toString(item.get("imdbId"),"");
                    if(imdbId.isBlank())imdbId=externalId(media,String.valueOf(item.get("tmdbId")));
                    RatingResult result=lookupRating(imdbId,title,date,media);
                    if(result.rateLimited()){
                        omdbRateLimitedUntil=Instant.now().plus(Duration.ofHours(24));
                        status="rate_limited";break;
                    }
                    if(result.failed()){
                        status="failed";break;
                    }
                    if(!result.checked())continue;
                    Double previous=(Double)item.get("imdbRating");
                    if(result.rating()!=null&&!Objects.equals(previous,result.rating()))updated++;
                    repository.saveImdbLookup(id,result.imdbId(),result.rating(),result.votes(),Instant.now());
                    checked++;
                    if(checked%25==0)repository.setRatingRefreshState("running",java.sql.Timestamp.from(start),null,checked,updated,
                        "Checked "+checked+" titles; updated "+updated+" ratings");
                }
                if(status.equals("rate_limited"))message="OMDb daily request limit reached. Saved "+updated+" ratings; remaining titles will retry tomorrow.";
                else if(status.equals("failed"))message="OMDb request failed. Check OMDB_API_KEY and try again; saved ratings were preserved.";
                else message="Rating refresh complete. Checked "+checked+" titles and updated "+updated+" ratings.";
            }
            repository.setRatingRefreshState(status,java.sql.Timestamp.from(start),java.sql.Timestamp.from(Instant.now()),checked,updated,message);
        }catch(Exception e){
            message="Rating refresh failed: "+e.getClass().getSimpleName();
            try{repository.setRatingRefreshState("failed",java.sql.Timestamp.from(start),java.sql.Timestamp.from(Instant.now()),checked,updated,message);}catch(Exception ignored){}
        }
        finally{ratingRefreshing.set(false);}
    }
    private boolean sameTitle(String a,String b){return normalize(a).equals(normalize(b));}
    private String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private boolean sameYear(String a,String b){return b.length()>=4&&a.equals(b.substring(0,4));}
    private String path(String p){return v3?p+(p.contains("?")?"&":"?")+"api_key={apiKey}":p;}
    private Object[] vars(Object... values){if(!v3)return values;Object[] a=Arrays.copyOf(values,values.length+1);a[values.length]=tmdbKey;return a;}
}
