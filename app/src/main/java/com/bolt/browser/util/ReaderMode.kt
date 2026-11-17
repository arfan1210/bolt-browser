package com.bolt.browser.util

/**
 * v1.3.39 — READER MODE (new code → Kotlin; menu-2 item 1 carried into
 * round 3, user-picked) + TEXT-REFLOW ZOOM (menu-3 item 14, read mode
 * only, per the user's "14 (read mode only)").
 *
 * A hand-rolled readability pass that runs IN the page (no dependency,
 * no network): score candidate containers by paragraph density, clone the
 * winner, strip everything non-content, and hand the HTML to
 * ReaderActivity which renders it locally with its own typography CSS.
 *
 * Reflow zoom = WebView textZoom — pinch in the reader changes the TEXT
 * size and the column re-wraps, instead of magnifying the layout.
 */
object ReaderMode {

    /**
     * The extraction script. Returns a JSON string {title, byline, html,
     * words} or null when no article-ish content was found. Written as one
     * compact IIFE so it survives evaluateJavascript stringification.
     */
    const val EXTRACT_JS = """
(function(){
  try{
    function txt(s){return (s||'').replace(/\s+/g,' ').trim();}
    var cand=[document.body];
    try{cand=cand.concat(Array.prototype.slice.call(document.querySelectorAll(
      'article,main,[role=main],.article,.post,.content,#content,.entry,.story')));}catch(e){}
    var best=null,bestScore=0;
    for(var i=0;i<cand.length;i++){
      var el=cand[i];if(!el)continue;
      var ps=el.querySelectorAll('p');
      var len=0,commas=0;
      for(var j=0;j<ps.length;j++){var t=txt(ps[j].textContent);len+=t.length;
        if(t.length>60)commas+=(t.match(/[,.;:]/g)||[]).length;}
      var cls=((el.className||'')+' '+(el.id||'')).toLowerCase();
      var bad=/(comment|share|footer|header|sidebar|nav|menu|advert|promo|related|widget)/.test(cls)?80:0;
      var score=len+commas*12-bad*10;
      if(score>bestScore){bestScore=score;best=el;}
    }
    if(!best||bestScore<180)return null;
    var clone=best.cloneNode(true);
    try{
      var kill=clone.querySelectorAll(
        'script,style,noscript,iframe,svg,form,button,aside,nav,footer,header,aside,figure figcaption,'
        +'.ad,.ads,.advert,.share,.sharing,.related,.comments,.comment,.sidebar,.promo,.newsletter,.paywall,'
        +'[role=navigation],[role=complementary],[aria-hidden=true]');
      for(var k=0;k<kill.length;k++){var p=kill[k].parentNode;if(p)p.removeChild(kill[k]);}
    }catch(e){}
    var html='';
    try{html=clone.innerHTML;}catch(e){html='';}
    if(txt(html.replace(/<[^>]+>/g,'')).length<200)return null;
    var by='';
    try{var m=document.querySelector('[itemprop=author],.byline,[rel=author],meta[name=author]');
      by=m?(m.getAttribute('content')||txt(m.textContent)).substring(0,80):'';}catch(e){}
    return JSON.stringify({title:document.title||'',byline:by,
      html:html,words:txt(clone.textContent).split(' ').length});
  }catch(e){return null;}
})()
"""

    /** the reader's typography CSS (light/dark/sepia selected by the
     *  activity; reflow zoom = textZoom on the WebView itself). */
    @JvmStatic
    fun css(dark: Boolean, sepia: Boolean, widthPct: Int, fontSizePx: Int, serif: Boolean): String {
        val bg: String
        val fg: String
        val dim: String
        when {
            dark -> { bg = "#121214"; fg = "#D6D6DB"; dim = "#8A8A93" }
            sepia -> { bg = "#F4ECD8"; fg = "#43382A"; dim = "#8C7E66" }
            else -> { bg = "#FFFFFF"; fg = "#1B1B20"; dim = "#6E6E7A" }
        }
        val fam = if (serif) "Georgia, 'Noto Serif', serif" else "'Roboto', 'Noto Sans', sans-serif"
        val link = if (dark) "#8AB4F8" else "#1A73E8"
        return """
body{margin:0;padding:0;background:$bg;color:$fg;
  font-family:$fam;line-height:1.65;
  -webkit-text-size-adjust:100%;}
.wrap{margin:0 auto;padding:28px 22px 64px;
  max-width:${widthPct}%;font-size:${fontSizePx}px;}
h1{font-size:1.55em;line-height:1.28;margin:0 0 6px;color:$fg;}
.byline{color:$dim;font-size:.82em;margin:0 0 22px;}
p{margin:0 0 1.15em;}
h2,h3{line-height:1.3;margin:1.6em 0 .7em;}
img,video{max-width:100%;height:auto;border-radius:10px;margin:1.2em 0;}
figure{margin:1.2em 0;}
figcaption{color:$dim;font-size:.8em;}
a{color:$link;text-decoration:none;}
blockquote{margin:1.2em 0;padding:2px 0 2px 14px;border-left:3px solid $dim;
  color:$dim;}
pre{overflow-x:auto;background:${if (dark) "#1E1E24" else "#F1F1F5"};
  padding:12px;border-radius:8px;font-size:.85em;}
code,pre{font-family:'Roboto Mono',monospace;}
ul,ol{padding-left:1.4em;}
hr{border:none;border-top:1px solid ${dim}33;margin:1.6em 0;}
table{border-collapse:collapse;max-width:100%;display:block;overflow-x:auto;}
td,th{border:1px solid ${dim}44;padding:6px 9px;font-size:.92em;}
"""
    }
}
