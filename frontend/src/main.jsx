import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Search, SlidersHorizontal, Clapperboard, Star, ChevronLeft, ChevronRight, Film, Tv, Sparkles, RefreshCw, ArrowUpRight, Play, ChevronDown, Check, GripVertical } from 'lucide-react';
import './style.css';

const API = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';
const platforms = ['All platforms', 'Netflix', 'Amazon Prime Video', 'JioHotstar'];
const pretty = (s) => s === 'Netflix' ? 'NETFLIX' : s === 'Amazon Prime Video' ? 'PRIME VIDEO' : 'JIOHOTSTAR';
const providerUrl = (platform, title) => {
  const q = encodeURIComponent(title);
  if (platform === 'Netflix') return `https://www.netflix.com/search?q=${q}`;
  if (platform === 'Amazon Prime Video') return `https://www.primevideo.com/search/ref=atv_nb_sr?phrase=${q}`;
  return `https://www.hotstar.com/in/search?q=${q}`;
};
const openTitle = async (row, platform, event) => {
  event?.preventDefault();
  const tab = window.open('about:blank', '_blank');
  if (!tab) return;
  tab.opener = null;
  try {
    const response = await fetch(`${API}/catalog/${encodeURIComponent(row.id)}/watch-link`);
    const data = response.ok ? await response.json() : null;
    tab.location = data?.url || providerUrl(platform, row.name);
  } catch {
    tab.location = providerUrl(platform, row.name);
  }
};
const imdbUrl = (row) => row?.imdbId ? `https://www.imdb.com/title/${row.imdbId}/` : `https://www.imdb.com/find/?q=${encodeURIComponent(row?.name || '')}`;
const primaryPlatform = (row) => ['Netflix', 'Amazon Prime Video', 'JioHotstar'].find(name => row?.platforms?.includes(name)) || row?.platforms?.[0] || 'Netflix';
const genreClass = (genre) => ({
  action:'genre-action', thriller:'genre-thriller', drama:'genre-drama', comedy:'genre-comedy',
  horror:'genre-horror', romance:'genre-romance', mystery:'genre-mystery', crime:'genre-crime',
  adventure:'genre-adventure', fantasy:'genre-fantasy', animation:'genre-animation', documentary:'genre-documentary',
  family:'genre-family', 'science fiction':'genre-scifi', 'sci-fi & fantasy':'genre-scifi', history:'genre-history',
  war:'genre-war', music:'genre-music', western:'genre-western', reality:'genre-reality', kids:'genre-kids', korean:'genre-korean', 'action & adventure':'genre-action', 'war & politics':'genre-war'
}[genre.toLowerCase()] || 'genre-default');
function ProviderMark({ name }) {
  if (name === 'Netflix') return <span className="provider-logo netflix-logo" aria-label="Netflix logo"><svg viewBox="0 0 20 24" aria-hidden="true"><path d="M3 2h4l6 14V2h4v20l-4-.7L7 8v13l-4 1z"/></svg></span>;
  if (name === 'Amazon Prime Video') return <span className="provider-logo prime-logo" aria-label="Prime Video logo"><b>prime</b><svg viewBox="0 0 34 10" aria-hidden="true"><path d="M2 2c9 7 21 7 29 1M26 1l5 2-1 4"/></svg></span>;
  return <span className="provider-logo hotstar-logo" aria-label="JioHotstar logo"><span>✦</span><b>JioHotstar</b></span>;
}
const saveCatalogCache = (key, value) => {
  try { localStorage.setItem(key, JSON.stringify({ savedAt: Date.now(), value })); }
  catch { for (const oldKey of Object.keys(localStorage).filter(k=>k.startsWith('reelscout:')).slice(0,10)) localStorage.removeItem(oldKey); try { localStorage.setItem(key, JSON.stringify({ savedAt: Date.now(), value })); } catch {} }
};

function App() {
  const [rows, setRows] = useState([]), [genres, setGenres] = useState([]), [totalResults, setTotalResults] = useState(0), [omdbUsage, setOmdbUsage] = useState({ requests: 0, limitReached: false });
  const [filtersOpen, setFiltersOpen] = useState(true); const [hoveredTitle,setHoveredTitle]=useState(null);
  const [draggedId, setDraggedId] = useState(null);
  const [platform, setPlatform] = useState('all'), [genre, setGenre] = useState([]), [genreOpen, setGenreOpen] = useState(false), [genreSearch, setGenreSearch] = useState(''), [type, setType] = useState('all');
  const [sort, setSort] = useState('latest'), [min, setMin] = useState('0'), [max, setMax] = useState('10'), [search, setSearch] = useState('');
  const [page, setPage] = useState(1), [totalPages, setTotalPages] = useState(1), [loading, setLoading] = useState(false), [error, setError] = useState(''), [source, setSource] = useState(''), [syncing,setSyncing]=useState(true), [syncMessage,setSyncMessage]=useState('Waiting for PostgreSQL catalog sync');
  const [ratingRefreshActive,setRatingRefreshActive]=useState(false), [ratingRefreshMessage,setRatingRefreshMessage]=useState(''), [catalogRevision,setCatalogRevision]=useState(0);
  useEffect(() => { fetch(`${API}/sync`, { method: 'POST' }).catch(() => {}); }, []);
  useEffect(() => {
    let live=true;
    const loadStatus=()=>fetch(`${API}/ratings/refresh-status`).then(r=>r.ok?r.json():Promise.reject()).then(status=>{
      if(!live)return;
      setOmdbUsage(status.usage||{requests:0,limitReached:false});
      if(status.status==='running')setRatingRefreshActive(true);
      else if(status.status==='rate_limited'||status.status==='failed')setRatingRefreshMessage(status.message||'The last IMDb refresh needs attention.');
    }).catch(()=>{});
    loadStatus();const interval=setInterval(loadStatus,60000);window.addEventListener('focus',loadStatus);
    return ()=>{live=false;clearInterval(interval);window.removeEventListener('focus',loadStatus);};
  }, []);
  useEffect(() => {
    if(!ratingRefreshActive)return;
    let live=true,timer;
    const poll=()=>fetch(`${API}/ratings/refresh-status`).then(async r=>{if(!r.ok)throw new Error();return r.json();}).then(status=>{
      if(!live)return;
      setRatingRefreshMessage(status.message||'Checking IMDb ratings…');setOmdbUsage(status.usage||{requests:0,limitReached:false});
      if(status.status==='running')timer=setTimeout(poll,3000);
      else{
        setRatingRefreshActive(false);
        try{for(const key of Object.keys(localStorage))if(key.startsWith('reelscout:')&&key!=='reelscout:genres')localStorage.removeItem(key);}catch{}
        setCatalogRevision(value=>value+1);
      }
    }).catch(()=>{if(live)timer=setTimeout(poll,5000);});
    timer=setTimeout(poll,750);
    return ()=>{live=false;clearTimeout(timer);};
  }, [ratingRefreshActive]);
  useEffect(() => {
    let live=true,timer;
    try { const cached=JSON.parse(localStorage.getItem('reelscout:genres')||'null')?.value; if(cached)setGenres(cached); } catch {}
    const load=()=>fetch(`${API}/genres`).then(r=>r.ok?r.json():Promise.reject()).then(data=>{if(live){setGenres(data);saveCatalogCache('reelscout:genres',data);}}).catch(()=>{if(live)timer=setTimeout(load,15000);});
    load(); return ()=>{live=false;clearTimeout(timer);};
  }, []);
  useEffect(() => {
    let live = true; setLoading(true); setError('');
    const query = new URLSearchParams({ platform, genre:genre.length?genre.join(','):'all', type, sort, q:search.trim(), minRating:min, maxRating:max, page:String(page) });
    const cacheKey = `reelscout:${query.toString()}`;
    let cached = null;
    try { cached = JSON.parse(localStorage.getItem(cacheKey) || 'null')?.value || null; } catch {}
    if(cached){setRows(cached.results||[]);setTotalResults(cached.totalResults||0);setTotalPages(Math.max(1,cached.totalPages||1));setSource(cached.ratingSource||'');setSyncing(!!cached.syncing);setSyncMessage(cached.syncMessage||'');setLoading(false);}
    let retryTimer;
    const load=()=>fetch(`${API}/catalog?${query}`).then(async r => { const data = await r.json(); if(!r.ok) throw new Error(data.message || 'Could not load the catalog.'); return data; })
      .then(data => { if(live){setRows(data.results||[]);setTotalResults(data.totalResults||0);setTotalPages(Math.max(1,data.totalPages||1));setSource(data.ratingSource||'');setSyncing(!!data.syncing);setSyncMessage(data.syncMessage||'');saveCatalogCache(cacheKey,data);if(data.syncing)retryTimer=setTimeout(load,15000);else if(data.ratingSource?.includes('unavailable'))retryTimer=setTimeout(load,60000);} })
      .catch(e => {if(live){if(!cached){setError(e.message.includes('Failed to fetch')?'Backend is not running yet. Retrying automatically…':e.message);setRows([]);} retryTimer=setTimeout(load,15000);}})
      .finally(()=>live&&setLoading(false));
    load();
    return ()=>{live=false;clearTimeout(retryTimer);};
  }, [platform, genre, type, sort, min, max, page, search, catalogRevision]);
  const visible = rows;
  const activeFilters = [];
  if(platform!=='all')activeFilters.push({label:platform,clear:()=>setPlatform('all')});
  genre.forEach(selected=>activeFilters.push({label:selected,clear:()=>setGenre(current=>current.filter(value=>value!==selected))}));
  if(type!=='all')activeFilters.push({label:type==='movie'?'Movies':'Series',clear:()=>setType('all')});
  if(min!=='0'||max!=='10')activeFilters.push({label:`IMDb ${min}–${max}`,clear:()=>{setMin('0');setMax('10')}});
  const reset = () => {setPlatform('all');setGenre([]);setGenreSearch('');setGenreOpen(false);setType('all');setSort('latest');setMin('0');setMax('10');setSearch('');setPage(1);};
  const moveMovie = (targetId) => {
    if(!draggedId||draggedId===targetId)return;
    setRows(current=>{const from=current.findIndex(item=>item.id===draggedId),to=current.findIndex(item=>item.id===targetId);if(from<0||to<0)return current;const next=[...current];const [moved]=next.splice(from,1);next.splice(to,0,moved);return next;});
    setDraggedId(null);
  };
  const refreshRatings=async()=>{
    setRatingRefreshMessage('Starting IMDb rating refresh…');
    try{
      const response=await fetch(`${API}/ratings/refresh`,{method:'POST'});
      const data=await response.json();
      if(!response.ok)throw new Error(data.message||'Could not start the rating refresh.');
      if(data.status==='rate_limited'){setRatingRefreshMessage('OMDb daily request limit reached. Try again tomorrow.');setOmdbUsage({...omdbUsage,limitReached:true});return;}
      setRatingRefreshMessage(data.message||'');
      if(data.status==='accepted')setRatingRefreshActive(true);
    }catch(e){setRatingRefreshMessage(e.message||'Could not reach the backend.');}
  };
  return <main className="app-shell">
    <header className="topbar"><a className="brand" href="#"><span className="brand-icon"><Clapperboard size={19}/></span><span>reel<span className="brand-light">scout</span></span></a><div className="top-note"><span className="live-dot"/> STREAMING IN INDIA</div><button className="filter-mobile" onClick={()=>setFiltersOpen(v=>!v)}><SlidersHorizontal size={16}/> {filtersOpen?'Hide filters':'Filters'}</button></header>
    <section className="hero hero-live" style={{'--hero-image':rows[0]?.poster?`url("${rows[0].poster}")`:'none'}}><div className="hero-car-track" aria-hidden="true"><span className="hero-road"/><svg className="hero-car" viewBox="0 0 64 30"><path className="car-body" d="M6 20v-5l7-2 7-8h22l9 8 7 2 1 5v4h-5a5 5 0 0 0-10 0H20a5 5 0 0 0-10 0H6z"/><path className="car-window" d="M22 7h8v6H17zM33 7h7l7 6H33z"/><circle className="car-wheel" cx="15" cy="24" r="4"/><circle className="car-wheel" cx="49" cy="24" r="4"/></svg></div><div className="hero-copy"><div className="eyebrow"><span className="live-dot"/> YOUR STREAMING GUIDE</div><h1>Tonight has<br/><em>a great watch.</em></h1><p>One living catalog across Netflix, Prime Video and JioHotstar.<br className="desktop"/> Pick a title to open its streaming availability page.</p><div className="hero-providers">{['Netflix','Amazon Prime Video','JioHotstar'].map((name)=><span className={`hero-provider hero-${name==='Netflix'?'netflix':name==='Amazon Prime Video'?'prime':'hotstar'}`} key={name}><ProviderMark name={name}/>{pretty(name)}</span>)}</div></div><div className="spotlight-wrap">{rows[0]?<a className="spotlight-card" href={providerUrl(primaryPlatform(rows[0]),rows[0].name)} onClick={e=>openTitle(rows[0],primaryPlatform(rows[0]),e)} target="_blank" rel="noreferrer"><img src={rows[0].poster} alt=""/><span className="spotlight-shade"/><span className="spotlight-kicker"><span className="live-dot"/> TODAY'S SPOTLIGHT</span><span className="spotlight-info"><b>{rows[0].name}</b><span>{rows[0].year||'Now streaming'} · {primaryPlatform(rows[0])}</span><span className="spotlight-action"><Play size={13} fill="currentColor"/> Open title page <ArrowUpRight size={14}/></span></span></a>:<div className="spotlight-empty"><div className="spotlight-glow"/><Film size={48}/><span>FRESH PICKS<br/>UPDATED DAILY</span></div>}</div></section>
    <section className="catalog-wrap">
      <div className="section-head"><div><div className="eyebrow muted">YOUR WATCHLIST STARTS HERE</div><h2>Explore what’s on <span className="title-dot">.</span></h2></div><div className="fresh-badge"><span/> {syncing?'SYNCING DATABASE':'LIVE CATALOG'}<small>{totalResults.toLocaleString()} titles</small></div></div>
      <div className="toolbar"><label className="searchbox"><Search size={17}/><input value={search} onChange={e=>{setSearch(e.target.value);setPage(1)}} placeholder="Search titles..."/><kbd>⌕</kbd></label><div className="toolbar-actions"><span className={`omdb-counter ${omdbUsage.limitReached?'limit-reached':''}`}>{omdbUsage.limitReached?'LIMIT REACHED · TOMORROW':`OMDb today · ${omdbUsage.requests||0} fetches`}</span><button className="refresh-button" onClick={refreshRatings} disabled={ratingRefreshActive||syncing||omdbUsage.limitReached}><RefreshCw size={14} className={ratingRefreshActive?'refresh-spinning':''}/>{ratingRefreshActive?'Refreshing IMDb…':'Refresh IMDb'}</button><button className="filter-toggle-button" onClick={()=>setFiltersOpen(v=>!v)}><SlidersHorizontal size={14}/>{filtersOpen?'Hide filters':'Show filters'}</button><button className="reset" onClick={reset}><SlidersHorizontal size={15}/> Reset</button></div></div>
      {ratingRefreshMessage&&<div className="rating-refresh-message" role="status">{ratingRefreshMessage}</div>}
      {activeFilters.length>0&&<div className="active-filters"><span className="active-filter-label">ACTIVE FILTERS</span>{activeFilters.map(item=><button className="active-filter-chip" key={item.label} onClick={()=>{item.clear();setPage(1)}}>{item.label}<b>×</b></button>)}<button className="clear-active" onClick={reset}>Clear all</button></div>}
      <div className={`layout ${filtersOpen?'':'filters-hidden'}`}>{filtersOpen&&<aside className="filters"><div className="filter-title"><SlidersHorizontal size={16}/> TUNE YOUR PICKS</div><div className="filter-group"><label>STREAMING SERVICE</label><div className="platform-pills">{platforms.map((p,i)=><button key={p} className={`platform-option platform-pill ${platform===(i===0?'all':p)?'selected':''}`} onClick={()=>{setPlatform(i===0?'all':p);setPage(1)}}>{i>0&&<span className={`platform-mark mark-${i}`}><ProviderMark name={p}/></span>}<span>{p}</span>{platform===(i===0?'all':p)&&<span className="check">✓</span>}</button>)}</div></div><div className="filter-group genre-filter-group"><label>GENRE <small>SELECT ANY</small></label><div className="genre-picker"><button type="button" className={`genre-picker-trigger ${genre.length?"has-selection":""}`} aria-expanded={genreOpen} onClick={()=>setGenreOpen(v=>!v)}><span>{genre.length?`${genre.length} genres selected`:"All genres"}</span><ChevronDown size={15}/></button>{genre.length>0&&<div className="selected-genres">{genre.map(g=><span className={`genre-chip ${genreClass(g)}`} key={g}>{g}<button type="button" aria-label={`Remove ${g}`} onClick={()=>{setGenre(current=>current.filter(value=>value!==g));setPage(1)}}>×</button></span>)}</div>}{genreOpen&&<div className="genre-menu"><label className="genre-search"><Search size={14}/><input type="search" value={genreSearch} onChange={e=>setGenreSearch(e.target.value)} placeholder="Find a genre"/></label><div className="genre-options">{[...new Set([...genres,...rows.flatMap(row=>row.genres||[])])].filter(g=>g.toLowerCase().includes(genreSearch.toLowerCase())).sort((a,b)=>a.localeCompare(b)).map(g=><button type="button" className={`genre-option ${genre.includes(g)?"checked":""}`} key={g} onClick={()=>{setGenre(current=>current.includes(g)?current.filter(value=>value!==g):[...current,g]);setPage(1)}}><span className="genre-checkbox">{genre.includes(g)&&<Check size={12}/>}</span><span className={`genre-chip ${genreClass(g)}`}>{g}</span></button>)}</div><div className="genre-menu-footer"><span>{genre.length?`${genre.length} selected · all matches first`:"Choose one or more"}</span><button type="button" onClick={()=>{setGenre([]);setPage(1)}} disabled={!genre.length}>Clear</button></div></div>}</div></div><div className="filter-group"><label>IMDb SCORE</label><div className="rating-range"><div><span>MIN</span><input type="number" min="0" max="10" step="0.5" value={min} onChange={e=>{setMin(e.target.value);setPage(1)}}/></div><b>—</b><div><span>MAX</span><input type="number" min="0" max="10" step="0.5" value={max} onChange={e=>{setMax(e.target.value);setPage(1)}}/></div></div><div className="score-slider-display"><span>MINIMUM SCORE</span><strong>{Number(min||0).toFixed(Number(min||0)%1?1:0)}<small>/10</small></strong></div><input className="range-slider" type="range" min="0" max="10" step="0.5" value={min} onChange={e=>{setMin(e.target.value);setPage(1)}}/></div><div className="filter-group"><label>FORMAT</label><div className="format-toggle">{[['all','All'],['movie','Movies'],['tv','Series']].map(([v,l])=><button className={type===v?'active':''} onClick={()=>{setType(v);setPage(1)}} key={v}>{l}</button>)}</div></div><div className="filter-group"><label>ORDER</label><select value={sort} onChange={e=>{setSort(e.target.value);setPage(1)}}><option value="latest">Newest first</option><option value="popular">Most popular</option></select></div></aside>}
      <div className="results"><div className="results-head"><span>{loading?'Finding something good…':<><strong>{totalResults.toLocaleString()}</strong> titles <span className="results-sub">· page {page} of {Math.max(1,totalPages)}</span></>}</span><span className="sort-caption">SORTED BY <b>{sort==='latest'?'NEWEST':'POPULARITY'}</b></span></div>{loading&&visible.length===0&&<div className="catalog-state"><span className="spinner"/> Loading your catalog…</div>}{!loading&&error&&<div className="catalog-state error"><b>Catalog unavailable</b><br/>{error}<br/><small>Check PostgreSQL and the backend environment.</small></div>}{!loading&&!error&&visible.length===0&&<div className="catalog-state">{syncing?`${syncMessage}. Picks will appear here automatically.`:omdbUsage.limitReached?<><b>OMDb's daily limit was reached.</b><br/>IMDb ratings can be refreshed tomorrow.</>:Number(min)>0&&omdbUsage.requests===0?<><b>No IMDb ratings have been loaded yet.</b><br/><button className="empty-refresh" onClick={refreshRatings}>Load IMDb ratings</button></>:'No titles match these filters. Try widening your search.'}</div>}{visible.length>0&&<div className="movie-grid">{visible.map((row,index)=><article className={`movie-card ${draggedId===row.id?"dragging":""}`} key={row.id} style={{'--card-index':index}} draggable={true} onMouseEnter={()=>setHoveredTitle(row)} onMouseLeave={()=>setHoveredTitle(null)} onFocusCapture={()=>setHoveredTitle(row)} onBlurCapture={e=>{if(!e.currentTarget.contains(e.relatedTarget))setHoveredTitle(null)}} onDragStart={e=>{setDraggedId(row.id);e.dataTransfer.effectAllowed="move"}} onDragOver={e=>e.preventDefault()} onDrop={e=>{e.preventDefault();moveMovie(row.id)}} onDragEnd={()=>setDraggedId(null)}><div className="movie-poster-frame"><a className="movie-poster-link" href={providerUrl(primaryPlatform(row),row.name)} onClick={e=>openTitle(row,primaryPlatform(row),e)} target="_blank" rel="noreferrer" aria-label={`Open ${row.name} streaming page`}><div className="movie-poster">{row.poster?<img src={row.poster} loading="lazy" alt={`${row.name} poster`}/>:<div className="poster-fallback"><Film size={28}/></div>}<span className="poster-vignette"/><span className="poster-play"><Play size={18} fill="currentColor"/></span><span className="poster-platform">{row.platforms.slice(0,2).map(p=><span className={`poster-logo poster-${p==='Netflix'?'netflix':p==='Amazon Prime Video'?'prime':'hotstar'}`} key={p}><ProviderMark name={p}/></span>)}</span></div></a><a className="poster-rating-link" href={imdbUrl(row)} target="_blank" rel="noreferrer" title={`Open ${row.name} on IMDb`} aria-label={`IMDb rating ${row.imdbRating} for ${row.name}`}><span className="poster-rating"><Star size={12} fill="currentColor"/>IMDb {row.imdbRating||'N/A'}</span></a></div><div className="movie-details"><a className="movie-title" href={providerUrl(primaryPlatform(row),row.name)} onClick={e=>openTitle(row,primaryPlatform(row),e)} target="_blank" rel="noreferrer" title="Open title availability · drag card to rearrange"><GripVertical className="drag-handle" size={14}/><span>{row.name}</span><ArrowUpRight size={14}/></a><div className="movie-meta"><span>{row.type==='movie'?'FILM':'SERIES'}</span><i/> <span>{row.year||'—'}</span>{row.imdbVotes!=null&&<><i/><span>{Number(row.imdbVotes).toLocaleString()} votes</span></>}</div><div className="movie-genres">{row.genres.map(g=><button type="button" className={`genre-chip genre-click ${genreClass(g)} ${genre.includes(g)?"active":""}`} key={g} aria-pressed={genre.includes(g)} onClick={()=>{setGenre(current=>current.includes(g)?current.filter(value=>value!==g):[...current,g]);setPage(1);setFiltersOpen(true)}}>{g}</button>)}</div><div className="movie-platform-links">{row.platforms.map(p=><a className={`watch-link watch-${p==='Netflix'?'netflix':p==='Amazon Prime Video'?'prime':'hotstar'}`} href={providerUrl(p,row.name)} onClick={e=>openTitle(row,p,e)} target="_blank" rel="noreferrer" key={p} title={`Open ${row.name} availability on ${p}`}><ProviderMark name={p}/><span>Watch here</span><ArrowUpRight size={11}/></a>)}</div></div></article>)}</div>}<div className="pagination"><span>PAGE {page} OF {Math.max(1,totalPages)}</span><div><button disabled={page<=1||loading} onClick={()=>setPage(page-1)}><ChevronLeft size={17}/></button><button disabled={page>=totalPages||loading} onClick={()=>setPage(page+1)}><ChevronRight size={17}/></button></div></div></div></div>
      <div className="footnote">{source&&<span>{source}. </span>} Cached in your browser and locally on this computer. Newest first sorts by release date; results are paginated and provider coverage may be incomplete. Streaming data comes from TMDB / JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB. <a href="https://www.themoviedb.org/" target="_blank" rel="noreferrer">About TMDB</a></div>
    </section><aside className={`side-spotlight ${hoveredTitle?"is-on":""}`} aria-hidden={!hoveredTitle}>{hoveredTitle&&<><span className="side-spotlight-lamp"/><span className="side-spotlight-beam"/><div className="side-spotlight-card">{hoveredTitle.poster&&<img src={hoveredTitle.poster} alt=""/>}<span className="side-spotlight-shade"/><div className="side-spotlight-copy"><b>{hoveredTitle.name}</b><span><Star size={14} fill="currentColor"/> IMDb {hoveredTitle.imdbRating||"N/A"}</span></div></div></>}</aside><footer>MADE FOR THE LOVE OF MOVIES <span>© 2026 REELSCOUT</span></footer>
  </main>;
}

createRoot(document.getElementById('root')).render(<App/>);
