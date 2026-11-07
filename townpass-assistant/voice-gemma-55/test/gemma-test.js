const assert=require('node:assert/strict');
const {createParser,normalizeResult}=require('../src/gemma-parser');
const ids=['bus-arrivals','road-report','senior-card','disability-card','accessible-toilets','accessible-taxi','member-registration','member-login','member-password','member-verification','member-profile','taipeipass-help','easycard-link','virtual-cards','student-card','child-card','library-card','pregnancy-card','welfare-cards','billing-overview','parking-payment','water-payment','school-payment','tax-payment','medical-payment','traffic-fines','payment-history','coupons','partner-offers','city-applications','counter-queue','disaster-report','police-service','participation','welfare-estimate','city-learning','neighborhood','library','hospital','vaccination','water-service','parent-child-centers','zoo','taipei-radio','essentials','subscriptions','city-news','map-explore','city-dashboard','road-construction','trash-routes','outdoors','smoking-areas','youbike','parking-locations'];
const catalog=ids.map(id=>({id,title:id,aliases:[],mode:id==='bus-arrivals'?'LIVE':'GUIDE'}));
const parser=createParser(catalog);
let count=0;
for(const id of ids){const p=parser.normalizeResult({intent:id},'測試');assert.equal(p.intent,id);count++;}
const cases=[
 ['299多久來？',{intent:'bus-arrivals',route:'299',missing_slots:"['stop']"},'bus-arrivals',['299'],['stop']],
 ['299 跟 235 哪個先到',{intent:'bus-arrivals',route:'299 235'},'bus-arrivals',['299','235'],['stop']],
 ['頂好市場的299到了沒',{intent:'bus-arrivals',route:'299',stop:'頂好市場'},'bus-arrivals',['299'],[]],
 ['人行道被機車堵住了',{intent:'road-report',action:'ANSWER'},'road-report',[],['location']],
 ['台北101怎麼去',{intent:'out-of-scope'},'out-of-scope',[],[]],
 ['停車費怎麼繳',{intent:'parking-payment'},'parking-payment',[],[]],
 ['忘記密碼',{intent:'member-password'},'member-password',[],[]],
 ['圖書借閱證',{intent:'library-card'},'library-card',[],[]],
 ['這是假的服務',{intent:'made-up-service'},'out-of-scope',[],[]]
];
for(const [text,raw,intent,routes,missing] of cases){const p=parser.normalizeResult(raw,text);assert.equal(p.intent,intent);assert.deepEqual(p.routes,routes);assert.deepEqual(p.missing_slots,missing);count++;}
assert.match(parser.buildSystemPrompt(),/parking-payment/);count++;
assert.match(parser.clarification(parser.normalizeResult({intent:'road-report'},'人行道被堵住')),/地點/);count++;
assert.throws(()=>parser.normalizeResult('not json'),/合法 JSON/);count++;
assert.throws(()=>normalizeResult({intent:'senior-card'},'測試'),/services.json/);count++;
console.log(`PASS ${count} catalog-aware Gemma parser checks (55 service IDs + 13 other checks)`);
