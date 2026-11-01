package com.family.tvguard.net

import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.service.GuardService
import com.family.tvguard.util.UsageAccessHelper
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LAN parent control API + simple mobile HTML admin.
 *
 * GET  /v1/status
 * GET  /v1/config        auth — quizIntervalMinutes, dailyQuotaMinutes, defaultUnlockHours
 * POST /v1/config        auth — same fields (partial update OK)
 * POST /v1/unlock        {"hours":2}  Authorization: Bearer <token> | ?token= | X-Token
 * POST /v1/full-unlock   {"hours":2}  thorough unlock: no quiz, quota does not re-lock
 * POST /v1/lock
 * POST /v1/cancel-unlock
 * GET  /admin/
 */
class AdminHttpServer(
    private val prefs: GuardPrefs,
    private val onStateChanged: (String) -> Unit
) : NanoHTTPD(GuardPrefs.HTTP_PORT) {

    private data class AdminStatus(
        val locked: Boolean,
        val rem: Long,
        val used: Long,
        val full: Boolean,
        val fullRem: Long,
        val quotaMin: Int,
        val quizMin: Int,
        val unlockHours: Double,
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri?.substringBefore('?') ?: "/"
        val method = session.method
        Log.i(TvGuardApp.TAG, "HTTP $method $uri")
        return try {
            when {
                uri == "/v1/status" && method == Method.GET -> statusJson()
                uri == "/v1/config" && method == Method.GET -> getConfig(session)
                uri == "/v1/config" && method == Method.POST -> postConfig(session)
                uri == "/v1/unlock" && method == Method.POST -> unlock(session)
                uri == "/v1/full-unlock" && method == Method.POST -> fullUnlock(session)
                uri == "/v1/lock" && method == Method.POST -> lock(session)
                uri == "/v1/cancel-unlock" && method == Method.POST -> cancel(session)
                uri == "/admin" || uri == "/admin/" || uri == "/" -> adminHtml()
                uri == "/admin/api/unlock" && method == Method.POST -> unlockFromForm(session)
                uri == "/admin/api/full-unlock" && method == Method.POST -> fullUnlockFromForm(session)
                uri == "/admin/api/lock" && method == Method.POST -> lockFromForm(session)
                uri == "/admin/api/cancel" && method == Method.POST -> cancelFromForm(session)
                uri == "/admin/api/force-quiz" && method == Method.POST -> forceQuizFromForm(session)
                uri == "/admin/api/config" && method == Method.POST -> configFromForm(session)
                uri == "/v1/force-quiz" && method == Method.POST -> forceQuiz(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TvGuardApp.TAG, "HTTP error: ${e.message}", e)
            jsonError(500, e.message ?: "error")
        }
    }

    private fun statusJson(): Response {
        // Read-only snapshot — do not flushSessionUsage (that would invent watch time).
        prefs.clampDailyUsedToElapsedDay()
        val now = System.currentTimeMillis()
        val app = TvGuardApp.instance
        val usageGranted = UsageAccessHelper.hasUsageAccess(app)
        val svc = GuardService.instance
        val o = JSONObject()
            .put("locked", prefs.isEffectivelyLocked(now))
            .put("prefsLocked", prefs.locked)
            .put("unlockUntilEpochMs", prefs.unlockUntilEpochMs)
            .put("remainingUnlockMs", prefs.remainingUnlockMs(now))
            .put("fullUnlock", prefs.isFullUnlockActive(now))
            .put("fullUnlockUntilEpochMs", prefs.fullUnlockUntilEpochMs)
            .put("remainingFullUnlockMs", prefs.remainingFullUnlockMs(now))
            .put("dailyUsedMs", prefs.dailyUsedMsSnapshot(now))
            .put("dailyDate", prefs.dailyDate)
            .put("dailyQuotaMs", prefs.dailyQuotaMs)
            .put("dailyQuotaMinutes", prefs.dailyQuotaMinutes)
            .put("remainingQuotaMs", prefs.remainingQuotaMs(now))
            .put("port", GuardPrefs.HTTP_PORT)
            .put("version", GuardService.VERSION_NAME)
            .put("versionCode", 17)
            .put("quizPending", prefs.quizPending)
            .put("msSinceLastQuiz", prefs.msSinceLastQuiz)
            .put("quizIntervalMs", prefs.quizIntervalMs)
            .put("quizIntervalMinutes", prefs.quizIntervalMinutes)
            .put("defaultUnlockHours", prefs.defaultUnlockHours)
            .put("currentQuizId", prefs.currentQuizId)
            .put("usageAccessGranted", usageGranted)
            .put("topDetectionMode", if (usageGranted) "usage_stats" else "heuristic")
            .put("lastTopPackage", svc?.lastTopPackage ?: JSONObject.NULL)
            .put("lastTopSource", svc?.lastTopSource ?: "none")
            .put("guardServiceRunning", svc != null)
            .put("deviceOwner", false)
            .put("deviceOwnerNote", "Skipped: requires factory-reset / dpm; unsafe for family Hisense TV")
            .put("keepAliveIntervalMs", 5L * 60_000)
        return jsonOk(o)
    }

    private fun unlock(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readBody(session)
        var hours = prefs.defaultUnlockHours
        if (body.isNotBlank()) {
            try {
                hours = JSONObject(body).optDouble("hours", hours)
            } catch (_: Exception) {}
        }
        if (hours <= 0) hours = prefs.defaultUnlockHours
        prefs.unlockHours(hours)
        onStateChanged("unlock ${hours}h")
        return statusJson()
    }

    private fun fullUnlock(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readBody(session)
        var hours = prefs.defaultUnlockHours
        if (body.isNotBlank()) {
            try {
                hours = JSONObject(body).optDouble("hours", hours)
            } catch (_: Exception) {}
        }
        if (hours <= 0) hours = prefs.defaultUnlockHours
        prefs.fullUnlockHours(hours)
        onStateChanged("full-unlock ${hours}h")
        return statusJson()
    }

    private fun lock(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        prefs.lockNow()
        onStateChanged("lock")
        return statusJson()
    }

    private fun cancel(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        prefs.cancelUnlock()
        onStateChanged("cancel-unlock")
        return statusJson()
    }

    /** Form posts from /admin HTML embed token in body field `token`. */
    private fun unlockFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token")) {
            // also accept header
            if (!authorized(session)) return redirectAdmin("token 无效")
        }
        val hours = (map["hours"] ?: session.parms["hours"] ?: prefs.defaultUnlockHours.toString()).toDoubleOrNull()
            ?: prefs.defaultUnlockHours
        prefs.unlockHours(hours)
        onStateChanged("unlock-form ${hours}h")
        return redirectAdmin("已解锁 ${hours} 小时")
    }

    private fun fullUnlockFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        val hours = (map["hours"] ?: session.parms["hours"] ?: prefs.defaultUnlockHours.toString()).toDoubleOrNull()
            ?: prefs.defaultUnlockHours
        prefs.fullUnlockHours(hours)
        onStateChanged("full-unlock-form ${hours}h")
        return redirectAdmin("已彻底解锁 ${hours} 小时（无答题/配额不重锁）")
    }

    private fun lockFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        prefs.lockNow()
        onStateChanged("lock-form")
        return redirectAdmin("已立即锁屏")
    }

    private fun cancelFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        prefs.cancelUnlock()
        onStateChanged("cancel-form")
        return redirectAdmin("已取消解锁")
    }

    private fun getConfig(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        return jsonOk(configJson())
    }

    private fun postConfig(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readBody(session)
        var quizMin: Int? = null
        var quotaMin: Int? = null
        var unlockH: Double? = null
        if (body.isNotBlank()) {
            try {
                val o = JSONObject(body)
                if (o.has("quizIntervalMinutes")) quizMin = o.getInt("quizIntervalMinutes")
                if (o.has("dailyQuotaMinutes")) quotaMin = o.getInt("dailyQuotaMinutes")
                if (o.has("defaultUnlockHours")) unlockH = o.getDouble("defaultUnlockHours")
            } catch (_: Exception) {}
        }
        prefs.updateConfig(quizMin, quotaMin, unlockH)
        onStateChanged(
            "config quiz=${prefs.quizIntervalMinutes}m quota=${prefs.dailyQuotaMinutes}m unlock=${prefs.defaultUnlockHours}h"
        )
        return jsonOk(configJson())
    }

    private fun configFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        val quizMin = (map["quizIntervalMinutes"] ?: session.parms["quizIntervalMinutes"])?.toIntOrNull()
        val quotaMin = (map["dailyQuotaMinutes"] ?: session.parms["dailyQuotaMinutes"])?.toIntOrNull()
        val unlockH = (map["defaultUnlockHours"] ?: session.parms["defaultUnlockHours"])?.toDoubleOrNull()
        prefs.updateConfig(quizMin, quotaMin, unlockH)
        onStateChanged(
            "config-form quiz=${prefs.quizIntervalMinutes}m quota=${prefs.dailyQuotaMinutes}m unlock=${prefs.defaultUnlockHours}h"
        )
        return redirectAdmin(
            "已保存：答题间隔 ${prefs.quizIntervalMinutes} 分钟 · 每日限额 ${prefs.dailyQuotaMinutes} 分钟 · 默认解锁 ${prefs.defaultUnlockHours} 小时"
        )
    }

    private fun configJson(): JSONObject = JSONObject()
        .put("quizIntervalMinutes", prefs.quizIntervalMinutes)
        .put("quizIntervalMs", prefs.quizIntervalMs)
        .put("dailyQuotaMinutes", prefs.dailyQuotaMinutes)
        .put("dailyQuotaMs", prefs.dailyQuotaMs)
        .put("defaultUnlockHours", prefs.defaultUnlockHours)
        .put("version", GuardService.VERSION_NAME)
        .put("versionCode", 17)

    private fun forceQuiz(session: IHTTPSession): Response {
        if (!authorized(session)) return unauthorized(session)
        prefs.quizPending = true
        prefs.sessionStartEpochMs = 0L
        prefs.ensureQuizSelected()
        onStateChanged("force-quiz")
        return statusJson()
    }

    private fun forceQuizFromForm(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val token = map["token"] ?: session.parms["token"]
        if (!prefs.tokenMatches(token) && !prefs.tokenMatches("Bearer $token") && !authorized(session)) {
            return redirectAdmin("token 无效")
        }
        prefs.quizPending = true
        prefs.sessionStartEpochMs = 0L
        prefs.ensureQuizSelected()
        onStateChanged("force-quiz-form")
        return redirectAdmin("已触发答题锁屏")
    }

    /**
     * Accept any of:
     * - Authorization: Bearer <token>  (or raw token)
     * - X-Token: <token>
     * - ?token=<token> query (NanoHTTPD parms)
     *
     * Some mobile in-app browsers (WeChat etc.) strip Authorization; query/X-Token remain.
     */
    private fun authorized(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"]
        if (prefs.tokenMatches(auth)) return true

        val xToken = session.headers["x-token"]
        if (!xToken.isNullOrBlank() &&
            (prefs.tokenMatches(xToken) || prefs.tokenMatches("Bearer $xToken"))
        ) return true

        val qToken = session.parms["token"]
        if (!qToken.isNullOrBlank() &&
            (prefs.tokenMatches(qToken) || prefs.tokenMatches("Bearer $qToken"))
        ) return true

        return false
    }

    private fun unauthorized(session: IHTTPSession): Response {
        val hasAuth = !session.headers["authorization"].isNullOrBlank()
        val hasX = !session.headers["x-token"].isNullOrBlank()
        val hasQ = !session.parms["token"].isNullOrBlank()
        Log.w(
            TvGuardApp.TAG,
            "HTTP 401 unauthorized uri=${session.uri} hasAuthorization=$hasAuth hasXToken=$hasX hasQueryToken=$hasQ"
        )
        return jsonError(401, "unauthorized")
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        return try {
            session.parseBody(map)
            map["postData"] ?: ""
        } catch (_: Exception) {
            try {
                val len = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (len <= 0) return ""
                val br = BufferedReader(InputStreamReader(session.inputStream))
                // NanoHTTPD usually consumes via parseBody
                ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun adminHtml(): Response {
        val st = run {
            prefs.clampDailyUsedToElapsedDay()
            val now = System.currentTimeMillis()
            val locked = prefs.isEffectivelyLocked(now)
            val rem = prefs.remainingUnlockMs(now) / 60000
            val used = prefs.dailyUsedMsSnapshot(now) / 60000
            val full = prefs.isFullUnlockActive(now)
            val fullRem = prefs.remainingFullUnlockMs(now) / 60000
            AdminStatus(
                locked, rem, used, full, fullRem,
                prefs.dailyQuotaMinutes, prefs.quizIntervalMinutes, prefs.defaultUnlockHours
            )
        }
        val html = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>家庭电视锁</title>
<style>
  body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:#0b1220;color:#fff;margin:0;padding:24px}
  h1{font-size:22px;margin:0 0 8px}
  h2{font-size:16px;margin:0 0 12px;color:#90caf9}
  .sub{color:#90a4ae;margin-bottom:20px;font-size:14px}
  .card{background:#152033;border-radius:16px;padding:20px;margin-bottom:16px}
  .status{font-size:18px;margin-bottom:8px}
  button{display:block;width:100%;padding:16px;margin:10px 0;border:0;border-radius:12px;font-size:18px;font-weight:600;color:#fff}
  .u{background:#1e88e5}.l{background:#e53935}.c{background:#546e7a}.s{background:#43a047}
  input{width:100%;padding:12px;border-radius:8px;border:1px solid #37474f;background:#0b1220;color:#fff;box-sizing:border-box;margin:8px 0}
  label{display:block;font-size:13px;color:#b0bec5;margin-top:8px}
  .ok{color:#81c784;font-size:14px}
  .hint{color:#78909c;font-size:12px;margin:4px 0 0}
  .row{display:flex;gap:10px}.row>div{flex:1}
</style>
</head>
<body>
  <h1>家庭电视锁</h1>
  <div class="sub">同一 WiFi · 家长控制 · M2.14 · Usage Access 建议开启</div>
  <div class="card">
    <div class="status">${when {
        st.locked -> "已锁定"
        st.full -> "彻底解锁中（剩余约 ${st.fullRem} 分钟）"
        else -> "解锁中（剩余约 ${st.rem} 分钟）"
      }}</div>
    <div class="sub" id="quotaLine">今日已看 ${st.used} / ${st.quotaMin} 分钟 · 答题间隔 ${st.quizMin} 分钟</div>
  </div>
  <div class="card">
    <label>家长 Token（电视锁屏页 / adb logcat / parent_token.txt）</label>
    <input id="token" type="text" placeholder="粘贴 parentToken" autocomplete="off"/>
    <button class="u" type="button" id="btnUnlock">解锁 ${st.unlockHours} 小时</button>
    <button class="u" type="button" style="background:#00897b" id="btnFullUnlock">彻底解锁 ${st.unlockHours} 小时</button>
    <button class="l" type="button" onclick="api('lock')">立即锁屏</button>
    <button class="c" type="button" onclick="api('cancel-unlock')">取消解锁</button>
    <button class="c" type="button" style="background:#8e24aa" onclick="api('force-quiz')">立即出题（测试）</button>
  </div>
  <div class="card">
    <h2>配置</h2>
    <p class="hint">修改后立即生效并持久保存。答题间隔 = 观看多久出题一次；每日限额 = 「2 小时」可看时长。</p>
    <div class="row">
      <div>
        <label>答题间隔（分钟）</label>
        <input id="quizIntervalMinutes" type="number" min="1" max="180" step="1" value="${st.quizMin}"/>
      </div>
      <div>
        <label>每日可看时长（分钟）</label>
        <input id="dailyQuotaMinutes" type="number" min="1" max="720" step="1" value="${st.quotaMin}"/>
      </div>
    </div>
    <label>普通/彻底解锁默认时长（小时）</label>
    <input id="defaultUnlockHours" type="number" min="0.25" max="24" step="0.25" value="${st.unlockHours}"/>
    <button class="s" type="button" onclick="saveConfig()">保存配置</button>
  </div>
  <p class="ok" id="msg"></p>
<script>
(function(){
  function qp(name){
    var s=location.search||'';
    if(s.charAt(0)==='?') s=s.substring(1);
    var parts=s.split('&');
    for(var i=0;i<parts.length;i++){
      var kv=parts[i].split('=');
      if(decodeURIComponent(kv[0]||'')===name){
        return decodeURIComponent((kv[1]||'').replace(/\+/g,' '));
      }
    }
    return '';
  }
  var fromQ=qp('token');
  var t=fromQ || localStorage.getItem('tvguard_token') || '';
  if(fromQ){ localStorage.setItem('tvguard_token', fromQ.trim()); }
  document.getElementById('token').value=t;
  document.getElementById('token').addEventListener('change',function(){
    localStorage.setItem('tvguard_token', this.value.trim());
  });
  document.getElementById('token').addEventListener('input',function(){
    localStorage.setItem('tvguard_token', this.value.trim());
  });
  var msgQ=qp('msg');
  if(msgQ){ document.getElementById('msg').textContent=msgQ; }
  window._unlockHours = ${st.unlockHours};
  function wireUnlockButtons(){
    var h=window._unlockHours||2;
    var bu=document.getElementById('btnUnlock');
    var bf=document.getElementById('btnFullUnlock');
    if(bu){ bu.textContent='解锁 '+h+' 小时'; bu.onclick=function(){ api('unlock',{hours:h}); }; }
    if(bf){ bf.textContent='彻底解锁 '+h+' 小时'; bf.onclick=function(){ api('full-unlock',{hours:h}); }; }
  }
  wireUnlockButtons();
  if(t){ loadConfig(); }
})();
function tok(){
  var t=document.getElementById('token').value.trim();
  if(!t){alert('请填写 Token');return null;}
  localStorage.setItem('tvguard_token', t);
  return t;
}
function applyStatus(s){
  var statusEl=document.querySelector('.status');
  var sub=document.getElementById('quotaLine');
  if(statusEl){
    if(s.locked){statusEl.textContent='已锁定';}
    else if(s.fullUnlock){statusEl.textContent='彻底解锁中（剩余约 '+Math.floor((s.remainingFullUnlockMs||0)/60000)+' 分钟）';}
    else{statusEl.textContent='解锁中（剩余约 '+Math.floor((s.remainingUnlockMs||0)/60000)+' 分钟）';}
  }
  var qMin=s.dailyQuotaMinutes!=null?s.dailyQuotaMinutes:Math.floor((s.dailyQuotaMs||0)/60000);
  var iMin=s.quizIntervalMinutes!=null?s.quizIntervalMinutes:Math.floor((s.quizIntervalMs||0)/60000);
  if(sub){sub.textContent='今日已看 '+Math.floor((s.dailyUsedMs||0)/60000)+' / '+qMin+' 分钟 · 答题间隔 '+iMin+' 分钟';}
  if(s.defaultUnlockHours!=null){
    window._unlockHours=s.defaultUnlockHours;
    var bu=document.getElementById('btnUnlock');
    var bf=document.getElementById('btnFullUnlock');
    if(bu){ bu.textContent='解锁 '+s.defaultUnlockHours+' 小时'; bu.onclick=function(){ api('unlock',{hours:s.defaultUnlockHours}); }; }
    if(bf){ bf.textContent='彻底解锁 '+s.defaultUnlockHours+' 小时'; bf.onclick=function(){ api('full-unlock',{hours:s.defaultUnlockHours}); }; }
  }
  if(s.quizIntervalMinutes!=null) document.getElementById('quizIntervalMinutes').value=s.quizIntervalMinutes;
  if(s.dailyQuotaMinutes!=null) document.getElementById('dailyQuotaMinutes').value=s.dailyQuotaMinutes;
  if(s.defaultUnlockHours!=null) document.getElementById('defaultUnlockHours').value=s.defaultUnlockHours;
}
function api(path, body){
  var t=tok(); if(!t) return;
  var msg=document.getElementById('msg');
  msg.textContent='请求中…';
  var url='/v1/'+path+'?token='+encodeURIComponent(t);
  fetch(url,{
    method:'POST',
    headers:{
      'Authorization':'Bearer '+t,
      'X-Token':t,
      'Content-Type':'application/json'
    },
    body: body?JSON.stringify(body):'{}'
  }).then(function(r){
    return r.json().then(function(j){ return {ok:r.ok,status:r.status,j:j}; }).catch(function(){
      return {ok:r.ok,status:r.status,j:null};
    });
  }).then(function(x){
    if(!x.ok){
      msg.textContent='失败 HTTP '+x.status+' '+(x.j&&x.j.error?x.j.error:'');
      return;
    }
    applyStatus(x.j);
    if(path==='lock'||path==='cancel-unlock'){ msg.textContent='已立即锁屏'; }
    else if(path==='full-unlock'){ msg.textContent='已彻底解锁（无答题/配额不重锁）'; }
    else if(path==='unlock'){ msg.textContent='已解锁'; }
    else if(path==='force-quiz'){ msg.textContent='已触发答题锁屏'; }
    else { msg.textContent='OK'; }
  }).catch(function(e){
    msg.textContent='网络错误: '+e;
  });
}
function loadConfig(){
  var t=document.getElementById('token').value.trim();
  if(!t) return;
  fetch('/v1/config?token='+encodeURIComponent(t),{
    headers:{'Authorization':'Bearer '+t,'X-Token':t}
  }).then(function(r){ return r.ok?r.json():null; }).then(function(j){
    if(!j) return;
    applyStatus(j);
  }).catch(function(){});
}
function saveConfig(){
  var t=tok(); if(!t) return;
  var body={
    quizIntervalMinutes: parseInt(document.getElementById('quizIntervalMinutes').value,10),
    dailyQuotaMinutes: parseInt(document.getElementById('dailyQuotaMinutes').value,10),
    defaultUnlockHours: parseFloat(document.getElementById('defaultUnlockHours').value)
  };
  var msg=document.getElementById('msg');
  msg.textContent='保存中…';
  fetch('/v1/config?token='+encodeURIComponent(t),{
    method:'POST',
    headers:{
      'Authorization':'Bearer '+t,
      'X-Token':t,
      'Content-Type':'application/json'
    },
    body: JSON.stringify(body)
  }).then(function(r){
    return r.json().then(function(j){ return {ok:r.ok,status:r.status,j:j}; }).catch(function(){
      return {ok:r.ok,status:r.status,j:null};
    });
  }).then(function(x){
    if(!x.ok){
      msg.textContent='保存失败 HTTP '+x.status+' '+(x.j&&x.j.error?x.j.error:'');
      return;
    }
    applyStatus(x.j);
    msg.textContent='已保存：答题间隔 '+x.j.quizIntervalMinutes+' 分钟 · 每日限额 '+x.j.dailyQuotaMinutes+' 分钟 · 默认解锁 '+x.j.defaultUnlockHours+' 小时';
  }).catch(function(e){
    msg.textContent='网络错误: '+e;
  });
}
</script>
</body>
</html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun redirectAdmin(msg: String): Response {
        val r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_PLAINTEXT, msg)
        r.addHeader("Location", "/admin/?msg=" + java.net.URLEncoder.encode(msg, "UTF-8"))
        return r
    }

    private fun jsonOk(o: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun jsonError(code: Int, msg: String): Response {
        val status = when (code) {
            401 -> Response.Status.UNAUTHORIZED
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }
        val o = JSONObject().put("error", msg)
        return newFixedLengthResponse(status, "application/json; charset=utf-8", o.toString())
    }
}
