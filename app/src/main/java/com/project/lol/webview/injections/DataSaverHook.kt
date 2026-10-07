package com.project.lol.webview.injections

/**
 * Data Saver injection.
 * - Disables video/canvas autoplay when active
 * - Sets audio preload to "none"
 * - Downgrades cover images via DataSaverManager.rewriteImageUrl logic (JS side)
 * - Blocks telemetry/ads via window.__splDataSaver flag read by FetchOverride
 * - Triggered by document-start bridge: window.__splDataSaver / window.__splDsImgQ
 */
object DataSaverHook {
    val CONTENT = """
        (function(){
            if(window.__splDsInstalled) return;
            window.__splDsInstalled = true;

            var imgQ = (window.__splDsImgQ || 'low').toLowerCase();
            var active = !!window.__splDataSaver;

            function rewriteImg(u){
                if(!u) return u;
                var t = (imgQ==='medium') ? '300' : '64';
                // misc.scdn.co images embed width: liked-songs-640.png -> liked-songs-300.png
                return u.replace(/-(640|300|160|64)(\.[a-z]+)$/i, '-'+t+'$2');
            }

            function swapImages(){
                var imgs = document.querySelectorAll('img[src*="i.scdn.co"], img[src*="misc.scdn.co"]');
                for(var i=0;i<imgs.length;i++){
                    var el = imgs[i];
                    if(el.__splDsRewritten) continue;
                    el.__splDsRewritten = true;
                    var src = el.getAttribute('src') || el.getAttribute('data-src') || '';
                    if(src) el.src = rewriteImg(src);
                }
            }

            function muteAutoplay(){
                var vs = document.querySelectorAll('video, canvas');
                for(var i=0;i<vs.length;i++){
                    var v = vs[i];
                    try{ v.removeAttribute('autoplay'); }catch(e){}
                    try{ v.muted = true; }catch(e){}
                    try{ v.preload = 'none'; }catch(e){}
                    if(v.tagName === 'VIDEO'){
                        try{
                            Object.defineProperty(v, 'play', {
                                value: function(){ return Promise.resolve(); },
                                configurable: true, writable: true
                            });
                        }catch(e){}
                    }
                }
            }

            function applyState(on){
                active = !!on;
                window.__splDataSaver = active;
                if(active){
                    muteAutoplay();
                    swapImages();

                    // MutationObserver for late-injected media/images
                    if(!window.__splDsObs){
                        window.__splDsObs = new MutationObserver(function(muts){
                            for(var m=0;m<muts.length;m++){
                                var nodes = muts[m].addedNodes;
                                for(var n=0;n<nodes.length;n++){
                                    var nd = nodes[n];
                                    if(nd.nodeType !== 1) continue;
                                    if(nd.tagName === 'VIDEO' || nd.tagName === 'CANVAS') muteAutoplay();
                                    else if(nd.querySelectorAll){
                                        var vs = nd.querySelectorAll('video, canvas');
                                        for(var k=0;k<vs.length;k++) muteAutoplay();
                                        var imgs = nd.querySelectorAll('img[src*="i.scdn.co"], img[src*="misc.scdn.co"]');
                                        for(var k=0;k<imgs.length;k++){
                                            var im = imgs[k];
                                            if(im.__splDsRewritten) continue;
                                            im.__splDsRewritten = true;
                                            var s = im.getAttribute('src') || im.getAttribute('data-src') || '';
                                            if(s) im.src = rewriteImg(s);
                                        }
                                    }
                                }
                            }
                        });
                        try{ window.__splDsObs.observe(document.body || document.documentElement, {childList:true, subtree:true}); }catch(e){}
                    }
                }else{
                    try{ if(window.__splDsObs) window.__splDsObs.disconnect(); }catch(e){}
                    window.__splDsObs = null;
                    // Restore: simple reload path would re-run bridge; we don't un-rewrite here.
                }
            }

            window.__splApplyDataSaver = applyState;
            applyState(active);

            // Re-run image swap on lazy-load / srcset changes
            document.addEventListener('load', function(e){
                var t = e.target;
                if(t && (t.tagName === 'IMG' || t.tagName === 'SOURCE')){
                    if(t.__splDsRewritten) return;
                    var s = t.getAttribute('src') || t.getAttribute('srcset') || t.getAttribute('data-src') || '';
                    if(s && (s.indexOf('i.scdn.co')!==-1 || s.indexOf('misc.scdn.co')!==-1)){
                        t.__splDsRewritten = true;
                        if(t.tagName === 'SOURCE'){
                            t.srcset = rewriteImg(s);
                        }else{
                            t.src = rewriteImg(s);
                        }
                    }
                }
            }, true);
        })();
    """.trimIndent()
}