/*! Bolt WebAuthn Bootstrap v1 (v1.4.31 PASSKEY LAZY): the <=3KB document-start
 * stub for the ~55KB webauthn_compat.js (detection here, lazy full layer). MIT. */
;(function(){
if(window.__BOLT_WEBAUTHN_BOOT__){return}
window.__BOLT_WEBAUTHN_BOOT__=1
var q=[];window.__BOLT_WEBAUTHN_PENDING=q;var asked=false
function req(){try{if(window.Bolt&&Bolt.passkeyLoad){Bolt.passkeyLoad();return}}catch(e){}setTimeout(req,300)}
function park(kind,options){
 if(!window.__BOLT_WEBAUTHN_FULL__&&!asked){asked=true;req()}
 return new Promise(function(res,rej){
  var e={kind:kind,options:options,resolve:res,reject:rej}
  q.push(e)
  setTimeout(function(){var i=q.indexOf(e);if(i>=0){q.splice(i,1);rej(new DOMException('timeout','NotAllowedError'))}},20000)
 })
}
function b64(s){s=String(s).replace(/-/g,'+').replace(/_/g,'/');while(s.length%4){s+='='}var b=atob(s),o=new Uint8Array(b.length);for(var i=0;i<b.length;i++){o[i]=b.charCodeAt(i)}return o.buffer}
function cpy(o){return Object.assign({},o)}
function cred(c){var y=cpy(c);y.id=b64(c.id);return y}
function obj(i){var o=typeof i==='string'?JSON.parse(i):i;if(!o||typeof o!=='object'){throw new TypeError('bad options')}return o}
function parseC(i){var o=obj(i),u=o.user||{},x={rp:cpy(o.rp||{}),user:{id:b64(u.id),name:u.name,displayName:u.displayName},challenge:b64(o.challenge),pubKeyCredParams:(o.pubKeyCredParams||[]).map(cpy)};if(o.timeout!=null)x.timeout=o.timeout;if(o.excludeCredentials)x.excludeCredentials=o.excludeCredentials.map(cred);if(o.authenticatorSelection)x.authenticatorSelection=cpy(o.authenticatorSelection);if(o.attestation!=null)x.attestation=o.attestation;if(o.extensions)x.extensions=cpy(o.extensions);return x}
function parseR(i){var o=obj(i),x={challenge:b64(o.challenge)};if(o.timeout!=null)x.timeout=o.timeout;if(o.rpId!=null)x.rpId=o.rpId;if(o.allowCredentials)x.allowCredentials=o.allowCredentials.map(cred);if(o.userVerification!=null)x.userVerification=o.userVerification;if(o.extensions)x.extensions=cpy(o.extensions);return x}
var P=null;try{P=window.PublicKeyCredential}catch(e){}
if(!P){P=function PKC(){};try{window.PublicKeyCredential=P}catch(e){}}
function st(n,f){try{if(!P[n]){Object.defineProperty(P,n,{value:f,writable:true,configurable:true})}}catch(e){}}
function YES(){return Promise.resolve(true)}
st('isUserVerifyingPlatformAuthenticatorAvailable',YES)
st('isPasskeyPlatformAuthenticatorAvailable',YES)
st('isConditionalMediationAvailable',YES)
st('getClientCapabilities',function(){return Promise.resolve({conditionalCreate:false,conditionalGet:true,hybridTransport:true,passkeyPlatformAuthenticator:true,userVerifyingPlatformAuthenticator:true,relatedOrigins:false,signalAllAcceptedCredentials:true,signalCurrentUserDetails:true,signalUnknownCredential:true})})
st('parseCreationOptionsFromJSON',parseC)
st('parseRequestOptionsFromJSON',parseR)
try{if(navigator.credentials){navigator.credentials.create=function(o){return park('create',o)};navigator.credentials.get=function(o){return park('get',o)}}}catch(e){}
})()
