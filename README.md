# ReelScout — streaming catalog

A Spring Boot + React app for finding movies and series reported as included with Netflix, Amazon Prime Video, or JioHotstar in India. The default view sorts recent releases first. Change the sort to popularity in the filter panel.

PostgreSQL is the catalog source of truth. A scheduled background job imports provider titles into `movie_finder`; the React page reads from the database and gets exact result and page counts for the rows actually stored.

The schema creates indexes for region/type filtering and both catalog sort orders, IMDb-rating ranges, and platform/genre filters. Existing databases can apply the non-blocking index migration with `psql -h 127.0.0.1 -U postgres -d movie_finder -f database/indexes.sql`.

## Data sources and limits

- Streaming availability and genres come from TMDB; availability is powered by TMDB's JustWatch data partnership. This is third-party data and may be incomplete or change after provider updates. It is not an official Netflix, Amazon, or JioHotstar catalog API.
- IMDb ratings are retrieved through OMDb using TMDB's IMDb title IDs. Without an OMDb key, ratings display as unavailable.
- TMDB API access requires a key. Include its required attribution in the app. See TMDB's API FAQ and terms.

## Requirements

- Java 17+, Maven 3.9+, Node.js 18+, PostgreSQL 14+
- TMDB API key or v4 read access token
- OMDb API key (needed for IMDb ratings)
- PostgreSQL password for the `postgres` user in `DB_PASSWORD`

## Run locally

Create a local backend environment file from the template and replace its placeholder values with your own (do not commit `backend/.env`):

```powershell
Copy-Item backend/.env.example backend/.env
notepad backend/.env
```

Spring Boot loads `backend/.env` for local runs. On hosting providers, add the same backend variables in the service's private environment settings. For Netlify, set `VITE_API_URL` to the deployed backend URL ending in `/api`.

Create the app database once:

```powershell
createdb -h 127.0.0.1 -U postgres movie_finder
```

Start the backend:

```powershell
cd "E:\Movie FInder\backend"
mvn spring-boot:run
```

In a second terminal, start React:

```powershell
cd "E:\Movie FInder\frontend"
npm install
npm run dev
```

Open http://localhost:5173. Opening the site requests a background sync if one is not already running and the previous one is older than a minute. A daily schedule is also enabled. `APP_SYNC_PAGES` controls pages imported per provider and media type (default 5). Provider catalogs are not guaranteed complete. Set `APP_REGION` to another ISO country code to change availability.

## API

- `GET /api/catalog?platform=all&type=all&genre=all&sort=latest&minRating=0&maxRating=10&page=1`
- `GET /api/genres`
- `GET /api/sync-status`
- `POST /api/sync` (starts a background import)

Supported platform values: `all`, `Netflix`, `Amazon Prime Video`, `JioHotstar`. Type values: `all`, `movie`, `tv`. Sort values: `latest`, `popular`.
