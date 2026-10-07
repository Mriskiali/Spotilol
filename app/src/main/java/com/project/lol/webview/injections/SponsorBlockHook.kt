package com.project.lol.webview.injections

/*
 * CREDIT: Spotilol - SponsorBlock.
 * Skips sponsor/intro/outro/interaction/music_offtopic segments in the
 * <audio>/<video> element while it plays.
 *
 * Segment supply: native side calls
 *   window.__splSbLoad(videoId)   - asks SponsorBlockClient (Ktor, LRU-cached)
 *   window.__splSbSet(videoId, d) - delivers [[start,end,"category"],...]
 * Track identity comes from TrackObserver (window.splOnTrackChange); segment
 * data is never fetched from JS (SponsorBlock has no CORS headers, and the
 * cache lives in Kotlin anyway).
 *
 * Skips fire 0.2s BEFORE the segment start (SKIP_LEAD) so the cut lands right
 * on the boundary instead of letting the first words of a sponsor bleed in.
 */

object SponsorBlockHook {
    val CONTENT = """(function(){
    if(window.__splSbInit) return;
    window.__splSbInit = 1;

    var SKIP_LEAD = 0.2,   // jump this early
        LEAD_TOL  = 0.005, // never jump more than SKIP_LEAD+tolerance ahead
        MIN_SKIP  = 0.3,   // ignore sub-second junk ranges
        COOLDOWN  = 1000,  // ms between seeks (loop-guard)
        TICK      = 250;   // poll interval

    var segs = [],         // sorted [[start,end,category],...]
        want = null,       // videoId we asked for
        lastSkip = 0,
        lastLen = -1,
        intv = null;

    window.__splSbSegs = function(){ return segs; };
    window.__splSbOn = function(){ return segs.length > 0; };

    function log(level, msg){
        try{ AndBridge.dbg(level, '[sponsorblock] ' + msg); }catch(e){}
    }

    // JS objects handed to native are strings; accept both.
    function toArr(d){
        if(typeof d === 'string'){ try{ d = JSON.parse(d); }catch(e){ d = null; } }
        return Object.prototype.toString.call(d) === '[object Array]' ? d : null;
    }

    window.__splSbSet = function(videoId, data){
        if(!videoId || videoId !== want) return false;
        var arr = toArr(data) || [], ok = [];
        for(var i = 0; i < arr.length; i++){
            var s = arr[i];
            if(!s || s.length < 2) continue;
            var a = parseFloat(s[0]), b = parseFloat(s[1]);
            if(isNaN(a) || isNaN(b) || a < 0 || b <= a || b - a < MIN_SKIP) continue;
            ok.push([a, b, String(s[2] || 'sponsor')]);
        }
        ok.sort(function(x, y){ return x[0] - y[0]; });
        segs = ok;
        if(segs.length) log('s', 'segments for ' + videoId + ': ' + segs.length);
        else log('s', 'no segments for ' + videoId);
        return true;
    };

    window.__splSbLoad = function(videoId){
        if(!videoId) return false;
        if(videoId === want && segs.length) return true; // already live
        want = videoId;
        try{ AndBridge.loadSponsorSegments(videoId); }catch(e){ log('w', 'no native sponsor bridge'); }
        return false;
    };

    function media(){
        if(typeof window.splMediaEl === 'function'){
            try{ var m = window.splMediaEl(); if(m) return m; }catch(e){}
        }
        var els = document.querySelectorAll('audio,video');
        for(var i = 0; i < els.length; i++){
            var e = els[i];
            if(!e.currentSrc && !e.src && !e.srcObject) continue;
            if(e.paused === false) return e;
        }
        return null;
    }

    function tick(){
        var m = media();
        if(!m || !segs.length) return;
        var t = m.currentTime;
        if(!(t >= 0)) return;
        for(var i = 0; i < segs.length; i++){
            var start = segs[i][0], end = segs[i][1];
            if(t >= end) continue;                      // already past this one
            var target = start - SKIP_LEAD;
            if(t < target) break;                       // sorted: rest are future
            if(t < target + SKIP_LEAD + LEAD_TOL){      // inside the lead window
                var now = Date.now();
                if(now - lastSkip < COOLDOWN) return;
                lastSkip = now;
                try{
                    m.currentTime = end;
                    log('s', 'skip ' + segs[i][2] + ' @' + t.toFixed(1) + ' -> ' + end.toFixed(1));
                }catch(e){ log('e', 'seek failed: ' + e); }
                return;
            }
            // past the lead window but still inside the segment: leave it alone
            // (user scrubbed here deliberately, or playback was paused through it).
        }
    }

    // TrackObserver (injected before this file) reports the new track here.
    if(typeof window.splOnTrackChange === 'function'){
        window.splOnTrackChange(function(uri, id){
            if(!id) return;
            segs = [];
            window.__splSbLoad(id);
        });
    } else {
        // Injection order fallback: poll the global TrackObserver sets up.
        setInterval(function(){
            var id = window.splTrackId;
            if(id && id !== want){ segs = []; window.__splSbLoad(id); }
        }, 1500);
    }

    intv = setInterval(tick, TICK);
    if(window.splTrackId) window.__splSbLoad(window.splTrackId);
    log('s', 'hook active');
})();"""
}
