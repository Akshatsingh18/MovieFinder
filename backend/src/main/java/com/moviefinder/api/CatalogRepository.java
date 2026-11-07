package com.moviefinder.api;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CatalogRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    @Transactional
    public void upsert(Map<String,Object> item, String region, String platform) {
        upsertBatch(List.of(item), region, platform);
    }

    @Transactional
    public void upsertBatch(List<Map<String,Object>> items, String region, String platform) {
        if(items.isEmpty()) return;
        List<Object[]> titles=new ArrayList<>(items.size()),platformRows=new ArrayList<>(items.size()),genreRows=new ArrayList<>();
        for(Map<String,Object> item:items){
            String id=String.valueOf(item.get("id")),media=String.valueOf(item.get("type"));
            long tmdbId=Long.parseLong(id.substring(0,id.lastIndexOf('-')));
            Object year=item.get("year");Integer releaseYear=year==null||String.valueOf(year).isBlank()?null:Integer.valueOf(String.valueOf(year));
            Double imdbRating=null;Object rating=item.get("imdbRating");if(rating!=null)try{imdbRating=Double.valueOf(String.valueOf(rating));}catch(NumberFormatException ignored){}
            String imdbId=Optional.ofNullable(item.get("imdbId")).map(String::valueOf).filter(s->!s.isBlank()&&!s.equals("null")).orElse(null);
            Object checked=item.get("imdbRatingCheckedAt");java.sql.Timestamp checkedAt=checked instanceof Instant instant?java.sql.Timestamp.from(instant):null;
            titles.add(new Object[]{id,tmdbId,media,region,item.get("name"),releaseYear,imdbId,imdbRating,checkedAt,item.get("imdbVotes"),item.getOrDefault("popularity",0),item.get("poster"),item.get("overview")});
            platformRows.add(new Object[]{id,platform});
            @SuppressWarnings("unchecked") List<String> genres=(List<String>)item.getOrDefault("genres",List.of());
            for(String genre:genres)genreRows.add(new Object[]{id,genre});
        }
        jdbc.batchUpdate("""
            INSERT INTO catalog_title (id,tmdb_id,media_type,region,title,release_year,imdb_id,imdb_rating,imdb_rating_checked_at,imdb_votes,popularity,poster,overview,last_synced)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,now())
            ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title, release_year=EXCLUDED.release_year,
            imdb_id=COALESCE(NULLIF(EXCLUDED.imdb_id,''),catalog_title.imdb_id), imdb_rating=COALESCE(EXCLUDED.imdb_rating,catalog_title.imdb_rating),
            imdb_votes=COALESCE(EXCLUDED.imdb_votes,catalog_title.imdb_votes),
            imdb_rating_checked_at=COALESCE(EXCLUDED.imdb_rating_checked_at,catalog_title.imdb_rating_checked_at),
            popularity=EXCLUDED.popularity, poster=EXCLUDED.poster, overview=EXCLUDED.overview, last_synced=now()
            """,titles);
        jdbc.batchUpdate("INSERT INTO title_platform(title_id,platform,last_seen) VALUES(?,?,now()) ON CONFLICT(title_id,platform) DO UPDATE SET last_seen=now()",platformRows);
        if(!genreRows.isEmpty())jdbc.batchUpdate("INSERT INTO title_genre(title_id,genre) VALUES(?,?) ON CONFLICT DO NOTHING",genreRows);
    }

    public Map<String,Object> imdbData(String id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT imdb_id,imdb_rating,imdb_votes,imdb_rating_checked_at FROM catalog_title WHERE id=?",id);
        return rows.isEmpty()?Map.of():rows.get(0);
    }

    public List<Map<String,Object>> ratingCandidates(String region) {
        return jdbc.query("""
            SELECT id, tmdb_id AS "tmdbId", media_type AS "mediaType", title,
                   release_year AS "releaseYear", imdb_id AS "imdbId", imdb_rating AS "imdbRating"
            FROM catalog_title
            WHERE region=?
            ORDER BY (imdb_rating IS NULL) DESC, imdb_rating_checked_at ASC NULLS FIRST, id
            """, (rs, row) -> {
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("id", rs.getString("id"));
                item.put("tmdbId", rs.getLong("tmdbId"));
                item.put("mediaType", rs.getString("mediaType"));
                item.put("title", rs.getString("title"));
                int year = rs.getInt("releaseYear");
                item.put("releaseYear", rs.wasNull() ? null : year);
                item.put("imdbId", rs.getString("imdbId"));
                double rating = rs.getDouble("imdbRating");
                item.put("imdbRating", rs.wasNull() ? null : rating);
                return item;
            }, region);
    }

    public void saveImdbLookup(String id, String imdbId, Double rating, Instant checkedAt) {
        jdbc.update("""
            UPDATE catalog_title SET
              imdb_id=COALESCE(NULLIF(?,''),imdb_id),
              imdb_rating=COALESCE(?,imdb_rating),
              imdb_rating_checked_at=COALESCE(?,imdb_rating_checked_at)
            WHERE id=?
            """, imdbId, rating, checkedAt == null ? null : java.sql.Timestamp.from(checkedAt), id);
    }

    public void saveImdbLookup(String id, String imdbId, Double rating, Long votes, Instant checkedAt) {
        jdbc.update("""
            UPDATE catalog_title SET imdb_id=COALESCE(NULLIF(?,''),imdb_id),
              imdb_rating=COALESCE(?,imdb_rating), imdb_votes=COALESCE(?,imdb_votes),
              imdb_rating_checked_at=COALESCE(?,imdb_rating_checked_at)
            WHERE id=?
            """, imdbId, rating, votes, checkedAt == null ? null : java.sql.Timestamp.from(checkedAt), id);
    }

    public void recordOmdbRequest() {
        java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        jdbc.update("INSERT INTO omdb_daily_usage(usage_date,request_count) VALUES(?,1) ON CONFLICT(usage_date) DO UPDATE SET request_count=omdb_daily_usage.request_count+1,updated_at=now()",today);
    }

    public void markOmdbKeyUnavailable(String fingerprint,String status) {
        if(!List.of("rate_limited","invalid").contains(status))throw new IllegalArgumentException("Unsupported OMDb key status");
        java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        jdbc.update("INSERT INTO omdb_key_daily_usage(usage_date,key_fingerprint,status) VALUES(?,?,?) ON CONFLICT(usage_date,key_fingerprint) DO UPDATE SET status=EXCLUDED.status,updated_at=now()",today,fingerprint,status);
    }

    public Set<String> unavailableOmdbKeyFingerprints() {
        java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        return new HashSet<>(jdbc.queryForList("SELECT key_fingerprint FROM omdb_key_daily_usage WHERE usage_date=? AND status IN ('rate_limited','invalid')",String.class,today));
    }

    public Map<String,Object> omdbDailyUsage(List<String> configuredKeyFingerprints) {
        java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        int requests=jdbc.query("SELECT request_count FROM omdb_daily_usage WHERE usage_date=?",rs->rs.next()?rs.getInt(1):0,today);
        Map<String,String> statuses=new HashMap<>();
        if(!configuredKeyFingerprints.isEmpty())jdbc.query("SELECT key_fingerprint,status FROM omdb_key_daily_usage WHERE usage_date=?",rs->{while(rs.next())statuses.put(rs.getString(1),rs.getString(2));return null;},today);
        long limited=configuredKeyFingerprints.stream().filter(fp->"rate_limited".equals(statuses.get(fp))).count();
        long invalid=configuredKeyFingerprints.stream().filter(fp->"invalid".equals(statuses.get(fp))).count();
        int remaining=(int)configuredKeyFingerprints.stream().filter(fp->!statuses.containsKey(fp)).count();
        boolean allRateLimited=!configuredKeyFingerprints.isEmpty()&&limited==configuredKeyFingerprints.size();
        jdbc.update("UPDATE omdb_daily_usage SET limit_reached=?,updated_at=now() WHERE usage_date=?",allRateLimited,today);
        Map<String,Object> usage=new LinkedHashMap<>();usage.put("date",today.toString());usage.put("requests",requests);usage.put("limitReached",allRateLimited);
        usage.put("keysConfigured",configuredKeyFingerprints.size());usage.put("keysRemaining",remaining);usage.put("keysRateLimited",limited);usage.put("keysInvalid",invalid);return usage;
    }

    public Map<String,Object> ratingRefreshState() {
        return jdbc.queryForMap("SELECT status,started_at AS \"startedAt\",finished_at AS \"finishedAt\",rows_checked AS \"rowsChecked\",ratings_updated AS \"ratingsUpdated\",message FROM rating_refresh_state WHERE id=1");
    }

    public void setRatingRefreshState(String status, java.sql.Timestamp started, java.sql.Timestamp finished,
                                     int rowsChecked, int ratingsUpdated, String message) {
        jdbc.update("UPDATE rating_refresh_state SET status=?,started_at=COALESCE(?,started_at),finished_at=?,rows_checked=?,ratings_updated=?,message=? WHERE id=1",
            status, started, finished, rowsChecked, ratingsUpdated, message);
    }

    public void removeStalePlatformRows(String platform, java.time.Instant startedAt) {
        jdbc.update("DELETE FROM title_platform WHERE platform=? AND last_seen < ?", platform, java.sql.Timestamp.from(startedAt));
    }

    public Map<String,Object> page(String region, String platform, String type, String genre, String query,
                                   double minRating, double maxRating, String sort, int page, int size,
                                   boolean syncing, String syncMessage) {
        String where = " WHERE t.region=:region AND (:type='all' OR t.media_type=:type) " +
            "AND (:platform='all' OR EXISTS (SELECT 1 FROM title_platform p WHERE p.title_id=t.id AND p.platform=:platform)) " +
            "AND (:genre='all' OR EXISTS (SELECT 1 FROM title_genre g WHERE g.title_id=t.id AND g.genre = ANY(string_to_array(:genre, ',')))) " +
            "AND (:query='' OR t.title ILIKE '%' || :query || '%') " +
            "AND (:minRating=0 OR t.imdb_rating >= :minRating) AND (t.imdb_rating IS NULL OR t.imdb_rating <= :maxRating)";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("region",region).addValue("type",type)
            .addValue("platform",platform).addValue("genre",genre).addValue("query",query).addValue("minRating",minRating)
            .addValue("maxRating",maxRating).addValue("limit",size).addValue("offset",(page-1)*size);
        Long total = named.queryForObject("SELECT count(*) FROM catalog_title t" + where, params, Long.class);
        String genreMatchOrder = genre.equals("all") ? "" : "(SELECT count(*) FROM title_genre matched WHERE matched.title_id=t.id AND matched.genre = ANY(string_to_array(:genre, ','))) DESC, ";
        String ordering = genreMatchOrder + (sort.equals("popular") ? "t.popularity DESC NULLS LAST, t.release_year DESC NULLS LAST, t.title" : "t.release_year DESC NULLS LAST, t.popularity DESC NULLS LAST, t.title");
        List<Map<String,Object>> rows = named.query("""
            SELECT t.*, COALESCE((SELECT array_agg(p.platform ORDER BY p.platform) FROM title_platform p WHERE p.title_id=t.id), ARRAY[]::text[]) AS platforms,
            COALESCE((SELECT array_agg(g.genre ORDER BY g.genre) FROM title_genre g WHERE g.title_id=t.id), ARRAY[]::text[]) AS genres
            FROM catalog_title t
            """ + where + " ORDER BY " + ordering + " LIMIT :limit OFFSET :offset", params, (rs,n) -> mapRow(rs));
        long count = total == null ? 0 : total;
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("results", rows); response.put("page", page); response.put("totalPages", Math.max(1,(int)Math.ceil(count/(double)size)));
        response.put("totalResults", count); response.put("region", region); response.put("syncing", syncing);
        response.put("syncMessage", syncMessage); response.put("ratingSource", "IMDb via OMDb");
        return response;
    }

    public List<String> genres(String region) {
        return jdbc.queryForList("SELECT DISTINCT g.genre FROM title_genre g JOIN catalog_title t ON t.id=g.title_id WHERE t.region=? ORDER BY g.genre", String.class, region);
    }

    public Map<String,Object> syncState() {
        return jdbc.queryForMap("SELECT status,started_at AS \"startedAt\",finished_at AS \"finishedAt\",rows_seen AS \"rowsSeen\",message FROM catalog_sync_state WHERE id=1");
    }

    public void setSyncState(String status, java.sql.Timestamp started, java.sql.Timestamp finished, int rows, String message) {
        jdbc.update("UPDATE catalog_sync_state SET status=?,started_at=COALESCE(?,started_at),finished_at=?,rows_seen=?,message=? WHERE id=1", status, started, finished, rows, message);
    }

    private Map<String,Object> mapRow(ResultSet rs) throws SQLException {
        Map<String,Object> item = new LinkedHashMap<>();
        item.put("id",rs.getString("id")); item.put("name",rs.getString("title")); item.put("type",rs.getString("media_type"));
        int year=rs.getInt("release_year"); item.put("year",rs.wasNull()?"":String.valueOf(year));
        double rating=rs.getDouble("imdb_rating"); item.put("imdbRating",rs.wasNull()?"N/A":String.valueOf(rating));
        long votes=rs.getLong("imdb_votes"); item.put("imdbVotes",rs.wasNull()?null:votes);
        item.put("imdbId",rs.getString("imdb_id"));
        item.put("platforms",array(rs.getArray("platforms"))); item.put("genres",array(rs.getArray("genres")));
        item.put("poster",Optional.ofNullable(rs.getString("poster")).orElse("")); item.put("overview",Optional.ofNullable(rs.getString("overview")).orElse(""));
        return item;
    }
    private List<String> array(Array array) throws SQLException {
        if (array==null) return List.of(); Object raw=array.getArray();
        if (raw instanceof Object[] values) return Arrays.stream(values).map(String::valueOf).toList();
        return List.of();
    }
}
