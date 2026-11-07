import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Search, SlidersHorizontal, Clapperboard, Star, ChevronLeft, ChevronRight, Film, Tv, X, Sparkles, RefreshCw } from 'lucide-react';
import './style.css';

const API = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';
const platforms = ['All platforms', 'Netflix', 'Amazon Prime Video', 'JioHotstar'];
const pretty = (s) => s === 'Netflix' ? 'NETFLIX' : s === 'Amazon Prime Video' ? 'PRIME VIDEO' : 'JIOHOTSTAR';
const saveCatalogCache = (key, value) => {
  try { localStorage.setItem(key, JSON.stringify({ savedAt: Date.now(), value })); }
  catch { for (const oldKey of Object.keys(localStorage).filter(k=>k.startsWith('reelscout:')).slice(0,10)) localStorage.removeItem(oldKey); try { localStorage.setItem(key, JSON.stringify({ savedAt: Date.now(), value })); } catch {} }
};

function App() {
  const [rows, setRows] = useState([]), [genres, setGenres] = useState([]);
  const [platform, setPlatform] = useState('all'), [genre, setGenre] = useState('all'), [type, setType] = useState('all');
  const [sort, setSort] = useState('latest'), [min, setMin] = useState('0'), [max, setMax] = useState('10'), [search, setSearch] = useState('');
  const [page, setPage] = useState(1), [totalPages, setTotalPages] = useState(1), [loading, setLoading] = useState(false), [error, setError] = useState(''), [source, setSource] = useState(''), [syncing,setSyncing]=useState(true), [syncMessage,setSyncMessage]=useState('Waiting for PostgreSQL catalog sync');
  const [ratingRefreshActive,setRatingRefreshActive]=useState(false), [ratingRefreshMessage,setRatingRefreshMessage]=useState(''), [catalogRevision,setCatalogRevision]=useState(0);
  useEffect(() => { fetch(`${API}/sync`, { method: 'POST' }).catch(() => {}); }, []);
  useEffect(() => {
    let live=true;
    fetch(`${API}/ratings/refresh-status`).then(r=>r.ok?r.json():Promise.reject()).then(status=>{
      if(!live)return;
      if(status.status==='running')setRatingRefreshActive(true);
      else if(status.status==='rate_limited'||status.status==='failed')setRatingRefreshMessage(status.message||'The last IMDb refresh needs attention.');
    }).catch(()=>{});
    return ()=>{live=false;};
  }, []);
  useEffect(() => {
    if(!ratingRefreshActive)return;
    let live=true,timer;
    const poll=()=>fetch(`${API}/ratings/refresh-status`).then(async r=>{if(!r.ok)throw new Error();return r.json();}).then(status=>{
      if(!live)return;
      setRatingRefreshMessage(status.message||'Checking IMDb ratings…');
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
    const query = new URLSearchParams({ platform, genre, type, sort, minRating:min, maxRating:max, page:String(page) });
    const cacheKey = `reelscout:${query.toString()}`;
    let cached = null;
    try { cached = JSON.parse(localStorage.getItem(cacheKey) || 'null')?.value || null; } catch {}
    if(cached){setRows(cached.results||[]);setTotalPages(Math.max(1,cached.totalPages||1));setSource(cached.ratingSource||'');setSyncing(!!cached.syncing);setSyncMessage(cached.syncMessage||'');setLoading(false);}
    let retryTimer;
    const load=()=>fetch(`${API}/catalog?${query}`).then(async r => { const data = await r.json(); if(!r.ok) throw new Error(data.message || 'Could not load the catalog.'); return data; })
      .then(data => { if(live){setRows(data.results||[]);setTotalPages(Math.max(1,data.totalPages||1));setSource(data.ratingSource||'');setSyncing(!!data.syncing);setSyncMessage(data.syncMessage||'');saveCatalogCache(cacheKey,data);if(data.syncing)retryTimer=setTimeout(load,15000);else if(data.ratingSource?.includes('unavailable'))retryTimer=setTimeout(load,60000);} })
      .catch(e => {if(live){if(!cached){setError(e.message.includes('Failed to fetch')?'Backend is not running yet. Retrying automatically…':e.message);setRows([]);} retryTimer=setTimeout(load,15000);}})
      .finally(()=>live&&setLoading(false));
    load();
    return ()=>{live=false;clearTimeout(retryTimer);};
  }, [platform, genre, type, sort, min, max, page, catalogRevision]);
  const visible = rows.filter(x => x.name.toLowerCase().includes(search.toLowerCase()));
  const reset = () => {setPlatform('all');setGenre('all');setType('all');setSort('latest');setMin('0');setMax('10');setSearch('');setPage(1);};
  const refreshRatings=async()=>{
    setRatingRefreshMessage('Starting IMDb rating refresh…');
    try{
      const response=await fetch(`${API}/ratings/refresh`,{method:'POST'});
      const data=await response.json();
      if(!response.ok)throw new Error(data.message||'Could not start the rating refresh.');
      setRatingRefreshMessage(data.message||'');
      if(data.status==='accepted')setRatingRefreshActive(true);
    }catch(e){setRatingRefreshMessage(e.message||'Could not reach the backend.');}
  };
  return <main className="app-shell">
    <header className="topbar"><a className="brand" href="#"><span className="brand-icon"><Clapperboard size={19}/></span><span>reel<span className="brand-light">scout</span></span></a><div className="top-note"><span className="live-dot"/> STREAMING IN INDIA</div><button className="filter-mobile" onClick={()=>document.querySelector('.filters')?.scrollIntoView({behavior:'smooth'})}><SlidersHorizontal size={16}/> Filters</button></header>
    <section className="hero"><div className="hero-copy"><div className="eyebrow"><Sparkles size={14}/> YOUR STREAMING GUIDE</div><h1>Find your next<br/><em>great watch.</em></h1><p>One search across Netflix, Prime Video and JioHotstar.<br className="desktop"/> Fresh picks, one simple place.</p></div><div className="hero-art"><div className="art-ring ring-one"/><div className="art-ring ring-two"/><div className="art-card card-back"><Film size={42}/></div><div className="art-card card-front"><Tv size={44}/></div><span className="art-star">✦</span></div></section>
    <section className="catalog-wrap">
      <div className="section-head"><div><div className="eyebrow muted">THE CATALOG</div><h2>Streaming now <span className="title-dot">.</span></h2></div><div className="fresh-badge"><span/> {syncing?'SYNCING DATABASE':'DATABASE CATALOG'}</div></div>
      <div className="toolbar"><label className="searchbox"><Search size={17}/><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search titles..."/><kbd>⌕</kbd></label><div className="toolbar-actions"><button className="refresh-button" onClick={refreshRatings} disabled={ratingRefreshActive||syncing}><RefreshCw size={14} className={ratingRefreshActive?'refresh-spinning':''}/>{ratingRefreshActive?'Refreshing IMDb…':'Refresh IMDb'}</button><button className="reset" onClick={reset}><SlidersHorizontal size={15}/> Reset filters</button></div></div>
      {ratingRefreshMessage&&<div className="rating-refresh-message" role="status">{ratingRefreshMessage}</div>}
      <div className="layout"><aside className="filters"><div className="filter-title"><SlidersHorizontal size={16}/> FILTER BY</div><div className="filter-group"><label>Streaming on</label>{platforms.map((p,i)=><button key={p} className={`platform-option ${platform===(i===0?'all':p)?'selected':''}`} onClick={()=>{setPlatform(i===0?'all':p);setPage(1)}}>{i>0&&<i className={`platform-mark mark-${i}`}>{i===1?'N':i===2?'▶':'J'}</i>}<span>{p}</span>{platform===(i===0?'all':p)&&<span className="check">✓</span>}</button>)}</div><div className="filter-group"><label>Genre</label><select value={genre} onChange={e=>{setGenre(e.target.value);setPage(1)}}><option value="all">All genres</option>{genres.map(g=><option key={g}>{g}</option>)}</select></div><div className="filter-group"><label>IMDb rating</label><div className="rating-range"><div><span>MIN</span><input type="number" min="0" max="10" step="0.5" value={min} onChange={e=>{setMin(e.target.value);setPage(1)}}/></div><b>—</b><div><span>MAX</span><input type="number" min="0" max="10" step="0.5" value={max} onChange={e=>{setMax(e.target.value);setPage(1)}}/></div></div><input className="range-slider" type="range" min="0" max="10" step="0.5" value={min} onChange={e=>{setMin(e.target.value);setPage(1)}}/></div><div className="filter-group"><label>Format</label><div className="format-toggle">{[['all','All'],['movie','Movies'],['tv','Series']].map(([v,l])=><button className={type===v?'active':''} onClick={()=>{setType(v);setPage(1)}} key={v}>{l}</button>)}</div></div><div className="filter-group"><label>Sort by</label><select value={sort} onChange={e=>{setSort(e.target.value);setPage(1)}}><option value="latest">Newest first</option><option value="popular">Most popular</option></select></div></aside>
      <div className="results"><div className="results-head"><span>{loading?'Finding titles…':<><strong>{visible.length}</strong> titles on this page</>}</span><span className="sort-caption">SORTED BY <b>{sort==='latest'?'NEWEST':'POPULARITY'}</b></span></div><div className="table-scroll"><table><thead><tr><th>TITLE</th><th>AVAILABLE ON</th><th>GENRE</th><th>YEAR</th><th>IMDb</th></tr></thead><tbody>{loading&&<tr><td colSpan="5" className="state"><span className="spinner"/> Loading database catalog…</td></tr>}{!loading&&error&&<tr><td colSpan="5" className="state error"><b>Catalog unavailable</b><br/>{error}<br/><small>Check PostgreSQL and the backend environment.</small></td></tr>}{!loading&&!error&&visible.length===0&&<tr><td colSpan="5" className="state">{syncing?`${syncMessage}. Titles will appear here automatically.`:'No titles match these filters. Try widening your search.'}</td></tr>}{!loading&&!error&&visible.map(row=><tr key={row.id}><td><div className="title-cell">{row.poster?<img src={row.poster} loading="lazy"/>:<div className="poster-fallback"><Film size={20}/></div>}<div><b>{row.name}</b><span>{row.type==='movie'?'Movie':'Series'}</span></div></div></td><td><div className="provider-list">{row.platforms.map(p=><span className={`provider provider-${p==='Netflix'?'netflix':p==='Amazon Prime Video'?'prime':'hotstar'}`} key={p}>{pretty(p)}</span>)}</div></td><td><div className="genre-list">{row.genres.slice(0,3).map(g=><span key={g}>{g}</span>)}</div></td><td>{row.year||'—'}</td><td><a className="imdb" href={row.imdbId?`https://www.imdb.com/title/${row.imdbId}/`:undefined} target="_blank" rel="noreferrer" title={row.imdbId?'Open this IMDb title':'IMDb title ID unavailable'}><Star size={14} fill="currentColor"/>{row.imdbRating==='N/A'?'N/A':row.imdbRating}</a></td></tr>)}</tbody></table></div><div className="pagination"><span>PAGE {page} OF {Math.max(1,totalPages)}</span><div><button disabled={page<=1||loading} onClick={()=>setPage(page-1)}><ChevronLeft size={17}/></button><button disabled={page>=totalPages||loading} onClick={()=>setPage(page+1)}><ChevronRight size={17}/></button></div></div></div></div>
      <div className="footnote">{source&&<span>{source}. </span>} Cached in your browser and locally on this computer. Newest first sorts by release date; results are paginated and provider coverage may be incomplete. Streaming data comes from TMDB / JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB. <a href="https://www.themoviedb.org/" target="_blank" rel="noreferrer">About TMDB</a></div>
    </section><footer>MADE FOR THE LOVE OF MOVIES <span>© 2026 REELSCOUT</span></footer>
  </main>;
}

createRoot(document.getElementById('root')).render(<App/>);
