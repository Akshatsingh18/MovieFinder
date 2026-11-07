-- Apply once to an existing Movie Finder PostgreSQL database.
-- CREATE INDEX CONCURRENTLY avoids blocking catalog reads and writes while building.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_title_region_latest
  ON catalog_title (region, release_year DESC NULLS LAST, popularity DESC NULLS LAST, title);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_title_region_popular
  ON catalog_title (region, popularity DESC NULLS LAST, release_year DESC NULLS LAST, title);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_title_region_type_latest
  ON catalog_title (region, media_type, release_year DESC NULLS LAST, popularity DESC NULLS LAST, title);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_title_region_type_popular
  ON catalog_title (region, media_type, popularity DESC NULLS LAST, release_year DESC NULLS LAST, title);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_title_region_rating
  ON catalog_title (region, imdb_rating) WHERE imdb_rating IS NOT NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_title_platform_platform_title
  ON title_platform (platform, title_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_title_platform_platform_last_seen
  ON title_platform (platform, last_seen);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_title_genre_genre_title
  ON title_genre (genre, title_id);

-- These old single-column indexes are covered by the new composite indexes.
DROP INDEX CONCURRENTLY IF EXISTS idx_catalog_title_year;
DROP INDEX CONCURRENTLY IF EXISTS idx_catalog_title_region_type;
DROP INDEX CONCURRENTLY IF EXISTS idx_title_platform_name;
DROP INDEX CONCURRENTLY IF EXISTS idx_title_genre_name;
