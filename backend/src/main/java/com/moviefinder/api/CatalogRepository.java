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
        String media = String.valueOf(item.get("type"));
        String id = String.valueOf(item.get("id"));
        long tmdbId = Long.parseLong(id.substring(0, id.lastIndexOf('-')));
        Object year = item.get("year");
        Integer releaseYear = year == null || String.valueOf(year).isBlank() ? null : Integer.valueOf(String.valueOf(year));
        Object rating = item.get("imdbRating");
        Double imdbRating = null;
        if (rating != null) try { imdbRating = Double.valueOf(String.valueOf(rating)); } catch (NumberFormatException ignored) {}
        String imdbId = Optional.ofNullable(item.get("imdbId")).map(String::valueOf).filter(s->!s.isBlank()&&!s.equals("null")).orElse(null);
        Object checked = item.get("imdbRatingCheckedAt");
        java.sql.Timestamp checkedAt = checked instanceof Instant instant ? java.sql.Timestamp.from(instant) : null;
        jdbc.update("""
            INSERT INTO catalog_title (id,tmdb_id,media_type,region,title,release_year,imdb_id,imdb_rating,imdb_rating_checked_at,popularity,poster,overview,last_synced)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,now())
            ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title, release_year=EXCLUDED.release_year,
            imdb_id=COALESCE(NULLIF(EXCLUDED.imdb_id,''),catalog_title.imdb_id), imdb_rating=COALESCE(EXCLUDED.imdb_rating,catalog_title.imdb_rating),
            imdb_rating_checked_at=COALESCE(EXCLUDED.imdb_rating_checked_at,catalog_title.imdb_rating_checked_at),
            popularity=EXCLUDED.popularity, poster=EXCLUDED.poster, overview=EXCLUDED.overview, last_synced=now()
            """, id, tmdbId, media, region, item.get("name"), releaseYear, imdbId, imdbRating, checkedAt,
            item.getOrDefault("popularity", 0), item.get("poster"), item.get("overview"));
        jdbc.update("INSERT INTO title_platform(title_id,platform,last_seen) VALUES(?,?,now()) ON CONFLICT(title_id,platform) DO UPDATE SET last_seen=now()", id, platform);
        @SuppressWarnings("unchecked") List<String> genres = (List<String>) item.getOrDefault("genres", List.of());
        for (String genre : genres) jdbc.update("INSERT INTO title_genre(title_id,genre) VALUES(?,?) ON CONFLICT DO NOTHING", id, genre);
    }

    public Map<String,Object> imdbData(String id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT imdb_id,imdb_rating,imdb_rating_checked_at FROM catalog_title WHERE id=?",id);
        return rows.isEmpty()?Map.of():rows.get(0);
    }

    public void removeStalePlatformRows(String platform, java.time.Instant startedAt) {
        jdbc.update("DELETE FROM title_platform WHERE platform=? AND last_seen < ?", platform, java.sql.Timestamp.from(startedAt));
    }

    public Map<String,Object> page(String region, String platform, String type, String genre,
                                   double minRating, double maxRating, String sort, int page, int size,
                                   boolean syncing, String syncMessage) {
        String where = " WHERE t.region=:region AND (:type='all' OR t.media_type=:type) " +
            "AND (:platform='all' OR EXISTS (SELECT 1 FROM title_platform p WHERE p.title_id=t.id AND p.platform=:platform)) " +
            "AND (:genre='all' OR EXISTS (SELECT 1 FROM title_genre g WHERE g.title_id=t.id AND g.genre=:genre)) " +
            "AND (:minRating=0 OR t.imdb_rating >= :minRating) AND (t.imdb_rating IS NULL OR t.imdb_rating <= :maxRating)";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("region",region).addValue("type",type)
            .addValue("platform",platform).addValue("genre",genre).addValue("minRating",minRating)
            .addValue("maxRating",maxRating).addValue("limit",size).addValue("offset",(page-1)*size);
        Long total = named.queryForObject("SELECT count(*) FROM catalog_title t" + where, params, Long.class);
        String ordering = sort.equals("popular") ? "t.popularity DESC NULLS LAST, t.release_year DESC NULLS LAST, t.title" : "t.release_year DESC NULLS LAST, t.popularity DESC NULLS LAST, t.title";
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
