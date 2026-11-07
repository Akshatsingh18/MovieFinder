package com.moviefinder.api;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"${app.frontend-origin:http://localhost:5173}", "http://127.0.0.1:5173"})
public class CatalogController {
    private final CatalogRepository repository;
    private final CatalogSyncService syncService;
    private final String region;
    public CatalogController(CatalogRepository repository, CatalogSyncService syncService, @Value("${app.region:IN}") String region) {
        this.repository=repository; this.syncService=syncService; this.region=region;
    }

    @GetMapping("/catalog")
    public Map<String,Object> catalog(@RequestParam(defaultValue="all") String platform,
        @RequestParam(defaultValue="all") String type, @RequestParam(defaultValue="all") String genre,
        @RequestParam(defaultValue="latest") String sort, @RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="0") double minRating,
        @RequestParam(defaultValue="10") double maxRating, @RequestParam(defaultValue="1") int page) {
        if(!List.of("all","Netflix","Amazon Prime Video","JioHotstar").contains(platform)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown platform");
        if(!List.of("all","movie","tv").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown media type");
        if(!List.of("latest","popular").contains(sort)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown sort order");
        if(page<1||minRating<0||maxRating>10||minRating>maxRating) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page or rating range");
        Map<String,Object> status=repository.syncState();
        String state=String.valueOf(status.getOrDefault("status","pending"));
        boolean syncing=syncService.isSyncing()||state.equals("pending")||state.equals("running");
        Object message=status.get("message");
        String syncMessage=message==null?(syncing?"Catalog is being imported into PostgreSQL":""):String.valueOf(message);
        return repository.page(region,platform,type,genre,q.trim(),minRating,maxRating,sort,page,20,syncing,syncMessage);
    }

    @GetMapping("/genres") public List<String> genres(){return repository.genres(region);}
    @GetMapping("/catalog/{id}/watch-link") public Map<String,Object> watchLink(@PathVariable String id){
        try{return syncService.titleWatchLink(id);}
        catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
    }
    @GetMapping("/sync-status") public Map<String,Object> syncStatus(){return repository.syncState();}
    @PostMapping("/sync") public Map<String,Object> syncNow(){boolean started=syncService.requestSyncIfStale();return Map.of("status",started?"accepted":"skipped","message",started?"Catalog sync started in the background.":"A recent sync is already running or fresh.");}
    @PostMapping("/ratings/refresh") public Map<String,Object> refreshRatings(){
        boolean started=syncService.requestRatingRefresh();
        return Map.of("status",started?"accepted":"busy","message",started?"IMDb rating refresh started in the background.":"A catalog or rating refresh is already running.");
    }
    @GetMapping("/ratings/refresh-status") public Map<String,Object> ratingRefreshStatus(){
        Map<String,Object> result=new java.util.LinkedHashMap<>(repository.ratingRefreshState());
        Map<String,Object> usage=syncService.omdbDailyUsage();
        if("running".equals(result.get("status"))&&!syncService.isRatingRefreshing()){
            result.put("status","idle");result.put("message","No IMDb refresh is currently running. You can start one now.");
        }else if("rate_limited".equals(result.get("status"))&&!Boolean.TRUE.equals(usage.get("limitReached"))){
            result.put("status","idle");result.put("message","OMDb keys are ready for another refresh.");
        }
        result.put("usage",usage);return result;
    }
}
