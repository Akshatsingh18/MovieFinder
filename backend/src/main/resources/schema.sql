CREATE TABLE IF NOT EXISTS catalog_title (
  id VARCHAR(100) PRIMARY KEY,
  tmdb_id BIGINT NOT NULL,
  media_type VARCHAR(10) NOT NULL,
  region VARCHAR(2) NOT NULL,
  title VARCHAR(500) NOT NULL,
  release_year INTEGER,
  imdb_id VARCHAR(20),
  imdb_rating DOUBLE PRECISION,
  imdb_rating_checked_at TIMESTAMPTZ,
  popularity DOUBLE PRECISION NOT NULL DEFAULT 0,
  poster TEXT,
  overview TEXT,
  last_synced TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tmdb_id, media_type, region)
);
ALTER TABLE catalog_title ADD COLUMN IF NOT EXISTS imdb_rating_checked_at TIMESTAMPTZ;
-- Catalog pagination: region plus the two supported sort orders, with media type variants.
CREATE INDEX IF NOT EXISTS idx_catalog_title_region_latest
  ON catalog_title (region, release_year DESC NULLS LAST, popularity DESC NULLS LAST, title);
CREATE INDEX IF NOT EXISTS idx_catalog_title_region_popular
  ON catalog_title (region, popularity DESC NULLS LAST, release_year DESC NULLS LAST, title);
CREATE INDEX IF NOT EXISTS idx_catalog_title_region_type_latest
  ON catalog_title (region, media_type, release_year DESC NULLS LAST, popularity DESC NULLS LAST, title);
CREATE INDEX IF NOT EXISTS idx_catalog_title_region_type_popular
  ON catalog_title (region, media_type, popularity DESC NULLS LAST, release_year DESC NULLS LAST, title);
-- Rating filters usually select scored titles only; keep the index compact.
CREATE INDEX IF NOT EXISTS idx_catalog_title_region_rating
  ON catalog_title (region, imdb_rating) WHERE imdb_rating IS NOT NULL;
CREATE TABLE IF NOT EXISTS title_platform (
  title_id VARCHAR(100) NOT NULL REFERENCES catalog_title(id) ON DELETE CASCADE,
  platform VARCHAR(80) NOT NULL,
  last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (title_id, platform)
);
-- The PK supports title-to-platform aggregation; these indexes support platform filtering and stale-row cleanup.
CREATE INDEX IF NOT EXISTS idx_title_platform_platform_title ON title_platform (platform, title_id);
CREATE INDEX IF NOT EXISTS idx_title_platform_platform_last_seen ON title_platform (platform, last_seen);
CREATE TABLE IF NOT EXISTS title_genre (
  title_id VARCHAR(100) NOT NULL REFERENCES catalog_title(id) ON DELETE CASCADE,
  genre VARCHAR(80) NOT NULL,
  PRIMARY KEY (title_id, genre)
);
-- The PK supports title-to-genre aggregation; this supports genre filtering and genre-list queries.
CREATE INDEX IF NOT EXISTS idx_title_genre_genre_title ON title_genre (genre, title_id);
CREATE TABLE IF NOT EXISTS catalog_sync_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  status VARCHAR(20) NOT NULL,
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  rows_seen INTEGER NOT NULL DEFAULT 0,
  message TEXT
);
INSERT INTO catalog_sync_state (id, status) VALUES (1, 'pending') ON CONFLICT (id) DO NOTHING;
