# ReelScout / Movie Finder — AI Agent Handoff

Use this document as the starting context for another AI agent. Treat it as an existing project with deployed services and a long history of changes; do not rebuild it from scratch.

## Project and repository

- Workspace/project path: `E:\Movie FInder`
- Git branch: `main`
- GitHub repository: `https://github.com/Akshatsingh18/MovieFinder.git`
- Current Git commit when this handoff was written: `2975a15` (`Run IMDb dataset fallback after quota exhaustion`)
- The working tree was clean when this document was created.
- Do **not** commit `backend/.env`, `.env.neon`, `.neon`, database passwords, API keys, or build folders. These are ignored already.

## Application purpose

ReelScout is a movie and TV streaming finder for India. It shows titles found on:

- Netflix
- Amazon Prime Video
- JioHotstar

Users can search, filter by platform, multiple genres, IMDb score, type, and sort order. Cards show TMDB poster/metadata, platform badges, genres, year, IMDb rating and votes when known. Platform/title clicks open an OTT-specific **search URL**. IMDb badges open IMDb.

## Stack and paths

| Area | Path | Notes |
|---|---|---|
| Spring Boot backend | `E:\Movie FInder\backend` | Java 17, Maven, Spring Boot 3.5.6, JDBC |
| React frontend | `E:\Movie FInder\frontend` | Vite + React |
| Database schema | `E:\Movie FInder\backend\src\main\resources\schema.sql` | Applied at backend startup |
| Extra indexes | `E:\Movie FInder\database\indexes.sql` | PostgreSQL indexes |
| Frontend entry | `E:\Movie FInder\frontend\src\main.jsx` | Main UI and API polling |
| Frontend styles | `E:\Movie FInder\frontend\src\style.css` | Cinematic UI, animations, responsive rules |
| Backend sync service | `E:\Movie FInder\backend\src\main\java\com\moviefinder\api\CatalogSyncService.java` | TMDB, OMDb, refresh workflows |
| Backend repository | `E:\Movie FInder\backend\src\main\java\com\moviefinder\api\CatalogRepository.java` | SQL and DB updates |
| Backend controller | `E:\Movie FInder\backend\src\main\java\com\moviefinder\api\CatalogController.java` | `/api` routes |

## Deployments

- Public frontend: `https://moviescouter.netlify.app/`
- Older Netlify URL: `https://snazzy-madeleine-40884e.netlify.app/`
- Render backend: `https://moviefinder-q47y.onrender.com`
- Backend API base: `https://moviefinder-q47y.onrender.com/api`
- Neon project: MovieFinder, default `main` branch, database `neondb`. Connection details are private in Render/local environment files and must not be copied into Git or chat.

### Deployment variables

Render backend should hold private values for:

```text
DB_URL
DB_USERNAME
DB_PASSWORD
TMDB_API_KEY
OMDB_API_KEYS
APP_REGION=IN
FRONTEND_ORIGIN=https://moviescouter.netlify.app
IMDB_DATASET_ENABLED
IMDB_DATASET_CRON
```

`OMDB_API_KEYS` accepts a comma-separated ordered list. The backend rotates keys only after OMDb declares a key rate-limited or invalid. Do not write the actual keys in a tracked file or handoff document.

Netlify needs:

```text
VITE_API_URL=https://moviefinder-q47y.onrender.com/api
```

When `FRONTEND_ORIGIN` or `VITE_API_URL` changes, redeploy the affected service. Render must be redeployed after backend changes; Netlify must be redeployed after frontend changes.

## Local development

The local app has previously run with:

```text
Frontend: http://127.0.0.1:5173
Backend:  http://127.0.0.1:8080/api
Local DB: PostgreSQL database movie_finder on localhost:5432
```

Create/maintain secrets only in `backend/.env`, copied from `backend/.env.example`.

Commands:

```powershell
cd "E:\Movie FInder\backend"
mvn spring-boot:run

cd "E:\Movie FInder\frontend"
npm install
npm run dev
```

Build checks used successfully:

```powershell
cd "E:\Movie FInder\backend"; mvn -q -DskipTests compile
cd "E:\Movie FInder\frontend"; npm run build
```

## Data model and flows

### Catalog

1. TMDB discover endpoints find current India streaming availability.
2. `CatalogSyncService` imports title, type, platform, poster, genres, release year, popularity, overview, and TMDB ID.
3. PostgreSQL is the catalog source of truth.
4. Main tables: `catalog_title`, `title_platform`, `title_genre`, `catalog_sync_state`, `rating_refresh_state`, `omdb_daily_usage`, `omdb_key_daily_usage`.
5. Search uses a trigram index. Filtering/sorting and pagination are done in PostgreSQL.

The catalog does **not** contain every movie ever released. It imports a limited number of TMDB provider pages (`APP_SYNC_PAGES`, default 5) and provider coverage comes from TMDB/JustWatch.

### IMDb rating flow

1. A user presses **Refresh IMDb**.
2. The backend checks catalog titles, looks up title IMDb IDs from TMDB if needed, then calls OMDb by IMDb ID first.
3. Ratings/votes are saved in `catalog_title.imdb_rating` and `catalog_title.imdb_votes` without erasing a prior rating on a failed OMDb response.
4. OMDb daily usage is counted in `omdb_daily_usage` and unavailable keys are tracked only as SHA-256 fingerprints in `omdb_key_daily_usage`.
5. If a key rate-limits, the next configured key is tried. The app says all keys are exhausted only when every configured key is marked rate-limited.

### IMDb dataset fallback — latest feature

Commits `b785148` and `2975a15` added an IMDb dataset fallback:

- URL: `https://datasets.imdbws.com/title.ratings.tsv.gz`
- It streams the gzipped TSV in the Render backend.
- It matches the TSV `tconst` against catalog `imdb_id` values.
- It saves `averageRating` and `numVotes` only for matching catalog titles.
- It runs after an OMDb refresh, including when OMDb quota is exhausted.
- It is scheduled daily at `04:30` Asia/Kolkata by default via `IMDB_DATASET_CRON`.
- It does not store the large downloaded file in Neon, Netlify, or Git; only matched rating values are saved in Neon PostgreSQL.

Important: IMDb’s public dataset has non-commercial/republication restrictions. Review the current IMDb dataset terms before continuing to expose those values in a public web product. A licensed data source or isolated personal/local-only use may be required.

## Current production problem — highest priority

As of the most recent diagnosis, the Netlify HTML site loaded successfully, but the Render backend did not return the catalog endpoint:

```text
GET https://moviefinder-q47y.onrender.com/api/catalog?page=1
=> timeout after 25 seconds with 0 bytes received
```

This makes `moviescouter.netlify.app` appear broken because the frontend waits for its API.

The likely cause is the newest IMDb dataset fallback running inside the same small free Render web service. It may be downloading/parsing the large IMDb ratings file or otherwise blocking/overloading the web service. This has not been proved from Render logs, but the correlation is strong and should be checked first.

### Immediate recovery

In Render environment variables, set:

```text
IMDB_DATASET_ENABLED=false
```

Then manually deploy the latest backend commit. This disables only the dataset fallback. TMDB catalog sync and OMDb key rotation remain available. Retest:

```text
https://moviefinder-q47y.onrender.com/api/catalog?page=1
https://moviefinder-q47y.onrender.com/api/ratings/refresh-status
```

### Better future design

Do not parse the large IMDb file inside the public Render web process. Use one of these instead:

1. Separate worker/cron service that imports ratings and does not serve HTTP requests.
2. A constrained import job with a hard execution time limit and progress/checkpoint storage.
3. A licensed IMDb API/dataset source appropriate for a public application.
4. Keep only OMDb and accept some missing IMDb ratings.

## Recent UI work

- Modern dark ReelScout layout with cinematic hero, ambient motion, floating spotlight poster, and a tiny moving car.
- Glassmorphism filter panel with platform pills and multi-select genre picker.
- Genre tags are colorful, clickable, and show all genres on cards.
- Multi-genre results rank titles matching all selected tags before titles matching only one selected tag.
- Movie cards are draggable for local visual reordering.
- A right-side hover spotlight previews poster/title/IMDb rating only on wide screens.
- Genre dropdown was changed from an overlay to inline layout so it pushes later filters down. Footer was shortened and sidebar widened to 260px on desktop.
- IMDb poster badge is a link to IMDb. Card/title/provider links use the OTT service’s search page, not the TMDB watch page.

## Important behavior decisions

- Do not claim direct Netflix/Prime/Hotstar playback links are available. The catalog stores provider names, not provider-specific playback/content IDs. Use provider search URLs as the safe fallback.
- The app had briefly opened TMDB watch pages; this was reverted in commit `7d44317` because the user preferred OTT searches.
- Refresh button should stay clickable while a refresh is active. The backend prevents duplicate jobs and returns a `busy` response.
- The frontend uses browser localStorage as a temporary catalog cache while Render wakes up.
- Render free tier can take 50+ seconds to wake after inactivity.

## Recent commit history

```text
2975a15 Run IMDb dataset fallback after quota exhaustion
b785148 Fill missing ratings from IMDb dataset
0a0183a Widen desktop filter sidebar
3f3233f Tidy genre picker footer
ba2e244 Keep genre picker inside filter panel
1f186e2 Clear stale IMDb refresh status after restart
486e6de Timeout stalled IMDb rating lookups
2183424 Rotate OMDb keys before reporting daily limit
7d44317 Restore OTT-specific title searches
0c67dd2 Add title spotlight and catalog improvements
```

## Diagnostic commands

Use read-only checks first:

```powershell
Invoke-RestMethod 'https://moviefinder-q47y.onrender.com/api/catalog?page=1'
Invoke-RestMethod 'https://moviefinder-q47y.onrender.com/api/ratings/refresh-status'
Invoke-RestMethod 'https://moviefinder-q47y.onrender.com/api/sync-status'
```

To inspect rating coverage in PostgreSQL:

```sql
SELECT count(*) AS rated_titles
FROM catalog_title
WHERE imdb_rating IS NOT NULL;

SELECT count(*) AS all_titles
FROM catalog_title;

SELECT title, imdb_id, imdb_rating, imdb_votes
FROM catalog_title
WHERE imdb_id = 'tt38793125';
```

## Handoff rules for the next agent

1. Preserve existing user changes; the project has been iterated many times.
2. Do not reveal, add, log, or commit secret values.
3. Diagnose the current Render API timeout before adding more UI features.
4. Build backend with `mvn -q -DskipTests compile` and frontend with `npm run build` after code changes.
5. Push only after checking Git diff/status and ensuring secret values are absent.
6. Clearly state whether Render, Netlify, or both need redeployment after each change.
