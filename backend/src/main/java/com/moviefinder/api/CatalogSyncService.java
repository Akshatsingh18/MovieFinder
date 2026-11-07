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
    private final int ratingRecheckDays;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private volatile String message="Waiting for first background sync";
    private final Map<String,Map<Integer,String>> genres=new ConcurrentHashMap<>();
    private final Map<String,String> imdbIds=new ConcurrentHashMap<>(), ratings=new ConcurrentHashMap<>();

    public CatalogSyncService(CatalogRepository repository, @Qualifier("catalogSyncExecutor") Executor syncExecutor,
      @Value("${TMDB_API_KEY:}") String tmdbKey,@Value("${OMDB_API_KEY:}") String omdbKey,
      @Value("${app.region:IN}") String region,@Value("${app.sync-pages:5}") int syncPages,
      @Value("${app.sync-open-cooldown-ms:21600000}") long syncOpenCooldownMs,
      @Value("${app.rating-recheck-days:14}") int ratingRecheckDays){
        if(tmdbKey.isBlank())throw new IllegalStateException("Set TMDB_API_KEY before starting the backend.");
        this.repository=repository;this.syncExecutor=syncExecutor;this.tmdbKey=tmdbKey;this.omdbKey=omdbKey;this.region=region;this.syncPages=Math.max(1,Math.min(syncPages,50));
        this.syncOpenCooldownMs=syncOpenCooldownMs;this.ratingRecheckDays=Math.max(1,ratingRecheckDays);
        this.v3=tmdbKey.matches("(?i)[0-9a-f]{32}");
        RestClient.Builder b=RestClient.builder().baseUrl(TMDB);if(!v3)b.defaultHeader("Authorization","Bearer "+tmdbKey);this.tmdb=b.build();
    }

    @Scheduled(initialDelay=10000,fixedDelayString="${app.sync-interval-ms:86400000}")
    public void scheduledSync(){requestSync();}

    public boolean requestSyncIfStale(){
        if(running.get())return false;
        Map<String,Object> state=repository.syncState();
        String status=String.valueOf(state.getOrDefault("status","pending"));
        if(status.equals("running"))return false;
        Object finished=state.get("finishedAt");
        Instant last=finished instanceof java.sql.Timestamp ts?ts.toInstant():finished instanceof java.time.OffsetDateTime odt?odt.toInstant():null;
        if(last!=null&&Duration.between(last,Instant.now()).toMillis()<syncOpenCooldownMs)return false;
        requestSync();return true;
    }

    public void requestSync(){
        if(!running.compareAndSet(false,true))return;
        syncExecutor.execute(this::syncCatalog);
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
                            for(Map<String,Object> hit:hits){repository.upsert(toItem(media,hit,service),region,service);seen++;}
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
    public String message(){return message;}

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
        String id=String.valueOf(hit.get("id")),key=media+"-"+id,itemId=id+"-"+media;
        String date=String.valueOf(hit.getOrDefault("release_date",hit.getOrDefault("first_air_date","")));
        List<String> tags=new ArrayList<>();Map<Integer,String> map=genres.computeIfAbsent(media,this::loadGenres);
        for(Number n:(List<Number>)hit.getOrDefault("genre_ids",List.of()))if(map.containsKey(n.intValue()))tags.add(map.get(n.intValue()));
        Map<String,Object> existing=repository.imdbData(itemId);
        String imdbId=Optional.ofNullable(existing.get("imdb_id")).map(String::valueOf).filter(s->!s.isBlank()&&!s.equals("null")).orElseGet(()->imdbIds.computeIfAbsent(key,k->externalId(media,id)));
        String title=String.valueOf(hit.getOrDefault("title",hit.get("name")));
        Object existingRating=existing.get("imdb_rating"),checkedObject=existing.get("imdb_rating_checked_at");
        Instant checkedAt=checkedObject instanceof java.sql.Timestamp ts?ts.toInstant():checkedObject instanceof java.time.OffsetDateTime odt?odt.toInstant():null;
        String rating;Instant updatedCheckedAt=checkedAt;
        if(existingRating instanceof Number n){rating=String.valueOf(n.doubleValue());}
        else if(checkedAt!=null&&Duration.between(checkedAt,Instant.now()).toDays()<ratingRecheckDays){rating="N/A";}
        else if(omdbKey.isBlank()){rating="N/A";}
        else {RatingResult result=lookupRating(imdbId,title,date,media);imdbId=result.imdbId();rating=result.rating();updatedCheckedAt=Instant.now();}
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",itemId);row.put("type",media);row.put("name",title);
        row.put("year",date.length()>=4?date.substring(0,4):"");row.put("genres",tags);row.put("platforms",List.of(platform));row.put("imdbId",imdbId);
        row.put("imdbRating",rating);row.put("imdbRatingCheckedAt",updatedCheckedAt);row.put("popularity",hit.getOrDefault("popularity",0));row.put("poster",hit.get("poster_path")==null?"":"https://image.tmdb.org/t/p/w342"+hit.get("poster_path"));
        row.put("overview",hit.getOrDefault("overview",""));return row;
    }
    private Map<Integer,String> loadGenres(String media){Map<String,Object> data=tmdb.get().uri(path("/genre/{media}/list?language=en"),vars(media)).retrieve().body(Map.class);Map<Integer,String> r=new HashMap<>();if(data!=null)for(Map<String,Object> g:(List<Map<String,Object>>)data.getOrDefault("genres",List.of()))r.put(((Number)g.get("id")).intValue(),String.valueOf(g.get("name")));return r;}
    private String externalId(String media,String id){try{Map<String,Object> d=tmdb.get().uri(path("/{media}/{id}/external_ids"),vars(media,id)).retrieve().body(Map.class);Object ext=d==null?null:d.get("imdb_id");return ext==null?"":String.valueOf(ext);}catch(Exception e){return "";}}
    private record RatingResult(String imdbId,String rating){}
    private RatingResult lookupRating(String imdbId,String title,String date,String media){
        if(!omdbKey.isBlank()&&!imdbId.isBlank())try{
            Map<String,Object> d=omdb.get().uri("https://www.omdbapi.com/?i={id}&apikey={key}",imdbId,omdbKey).retrieve().body(Map.class);
            if(d!=null&&"True".equalsIgnoreCase(String.valueOf(d.get("Response"))))return new RatingResult(imdbId,String.valueOf(d.getOrDefault("imdbRating","N/A")));
        }catch(Exception ignored){}
        if(omdbKey.isBlank()||date.length()<4)return new RatingResult(imdbId,"N/A");
        try{
            String kind=media.equals("tv")?"series":"movie",year=date.substring(0,4);
            Map<String,Object> d=omdb.get().uri("https://www.omdbapi.com/?t={title}&y={year}&type={type}&apikey={key}",title,year,kind,omdbKey).retrieve().body(Map.class);
            if(d!=null&&"True".equalsIgnoreCase(String.valueOf(d.get("Response")))&&sameTitle(title,String.valueOf(d.getOrDefault("Title","")))&&sameYear(year,String.valueOf(d.getOrDefault("Year",""))))
                return new RatingResult(String.valueOf(d.getOrDefault("imdbID","")),String.valueOf(d.getOrDefault("imdbRating","N/A")));
        }catch(Exception ignored){}
        return new RatingResult(imdbId,"N/A");
    }
    private boolean sameTitle(String a,String b){return normalize(a).equals(normalize(b));}
    private String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private boolean sameYear(String a,String b){return b.length()>=4&&a.equals(b.substring(0,4));}
    private String path(String p){return v3?p+(p.contains("?")?"&":"?")+"api_key={apiKey}":p;}
    private Object[] vars(Object... values){if(!v3)return values;Object[] a=Arrays.copyOf(values,values.length+1);a[values.length]=tmdbKey;return a;}
}
