/* TownPass 55-service catalog-aware Gemma JSON parser. Node + browser, no dependencies. */
(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.TownPassGemmaParser=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
'use strict';
const clean=v=>typeof v==='string'&&v.trim()?v.trim():null;
const normalize=v=>String(v??'').normalize('NFKC').trim();
function parseJson(raw){
 if(raw&&typeof raw==='object'&&!Array.isArray(raw))return raw;
 if(typeof raw!=='string')throw Error('模型輸出不是 JSON');
 let s=raw.trim().replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,'');
 try{const p=JSON.parse(s);if(!p||Array.isArray(p)||typeof p!=='object')throw Error();return p;}catch{throw Error('模型輸出不是合法 JSON，請重新推論或使用規則式備援');}
}
function validateCatalog(catalog){
 if(!Array.isArray(catalog)||!catalog.length)throw Error('請傳入 services.json 的服務陣列，不能使用舊版六項白名單');
 const seen=new Set();return catalog.map(s=>{
  if(!s||typeof s.id!=='string'||!s.id.trim()||seen.has(s.id))throw Error('服務清單有無效或重複的 id');
  seen.add(s.id);return {id:s.id,title:clean(s.title)||s.id,aliases:Array.isArray(s.aliases)?s.aliases.filter(x=>typeof x==='string'):[],summary:clean(s.summary)||'',mode:s.mode};
 });
}
function extractRoutes(text){
 const s=normalize(text), found=[];
 for(const m of s.matchAll(/(?<![\dA-Za-z])(?:紅|藍|棕|橘|綠|小)?\d{1,4}(?:區間|副線)?(?!\d)/g)){
  const r=m[0];if(r==='1999'||(r==='101'&&/台北101|臺北101/.test(s)))continue;
  if(!found.includes(r))found.push(r);
 }return found;
}
function isBusUtterance(s){return /公車|到站|站牌|哪班先到|幾號車|等幾分鐘|多久來|到哪了|到了沒|\d{1,4}\s*(?:跟|和|與)\s*\d{1,4}/.test(s)||/^\s*(?:紅|藍|棕|橘|綠|小)?\d{1,4}\s*(?:多久|幾點|還要|到|來)/.test(s);}
function createParser(catalog){
 const services=validateCatalog(catalog),allowed=new Set(services.map(s=>s.id));
 function normalizeResult(raw,utterance=''){
  const p=parseJson(raw),text=normalize(utterance);
  let intent=clean(p.intent);
  if(!allowed.has(intent))intent='out-of-scope';
  let routes=[];
  if(intent==='bus-arrivals'){
   for(const v of [p.routes,p.route].flat(Infinity))if(v!=null)for(const r of extractRoutes(String(v)))if(!routes.includes(r))routes.push(r);
   if(isBusUtterance(text))for(const r of extractRoutes(text))if(!routes.includes(r))routes.push(r);
  }
  const stop=intent==='bus-arrivals'?clean(p.stop):null;
  const location=intent==='road-report'?clean(p.location):null;
  const query_type=intent==='bus-arrivals'?(routes.length>1||/哪(?:個|班)先到|比較/.test(text)?'compare':'arrival'):null;
  const missing_slots=[];
  if(intent==='bus-arrivals'){if(!routes.length)missing_slots.push('route');if(!stop)missing_slots.push('stop');}
  if(intent==='road-report'&&!location)missing_slots.push('location');
  const action=intent==='out-of-scope'?'OUT_OF_SCOPE':missing_slots.length?'CLARIFY':'ANSWER';
  return {intent,query_type,routes,route:routes.length===1?routes[0]:null,stop,location,missing_slots,action};
 }
 function clarification(p){if(p.action!=='CLARIFY')return null;if(p.intent==='bus-arrivals')return p.missing_slots.includes('route')?'請問要查哪一路公車？':`請問要查${p.routes.length?' '+p.routes.join('、')+' 路公車':''}的哪個站牌？`;if(p.intent==='road-report')return '請問問題發生在哪個地點？';return '請再說明一下你的需求。';}
 function buildSystemPrompt(){
  const list=services.map(s=>`${s.id}｜${s.title}｜別名：${s.aliases.join('、')||'無'}`).join('\n');
  return `你是臺北通繁體中文服務意圖分類器，不是聊天機器人。只能選下列服務 ID 或 out-of-scope。不要創造 ID。\n${list}\n\n只輸出一行合法 JSON：{"intent":"服務ID或out-of-scope","routes":[],"stop":null,"location":null}。routes 必須是字串陣列；stop 是公車站名；location 是道路陳情的明確地點。不能從語句推斷地點時用 null。不要編造公車到站時間、站牌、政策。多條公車全部列入 routes，例如「299跟235哪個先到」輸出 {"intent":"bus-arrivals","routes":["299","235"],"stop":null,"location":null}。台北101旅遊或導航不是公車。遇到語意不明或清單外問題，輸出 out-of-scope。只分類最新一則使用者輸入。`;
 }
 return {normalizeResult,clarification,buildSystemPrompt,services};
}
function normalizeResult(raw,utterance,catalog){return createParser(catalog).normalizeResult(raw,utterance);}
return {createParser,normalizeResult,validateCatalog,extractRoutes,parseJson};
});
