// Host-only bounded reference model for atto-spec#313 / conformance#725.
// No parser, real guest, generic binder, borrow checker, copy graph, layout or ABI proof.
// Run: node reference/collection-subtypes-v1.mjs
function referenceTests() {
  "use strict";
  const fail = code => { throw new Error(code); };
  const mapRoot = Object.freeze({kind:"map", id:Symbol("Map"), kv:["string","int"], fields:new Map(), methods:new Map([["size",Symbol("Map.size")],["get",Symbol("Map.get")],["put",Symbol("Map.put")]]), parent:null});
  const recordRoot = Object.freeze({kind:"record", id:Symbol("Record"), fields:new Map(), methods:new Map(), parent:null});
  // Model consumes resolved descriptors, not source text or untrusted runtime values.
  function derive(parent, id, fields=[], methods=[], options={}) {
    if (!parent || !["map","record"].includes(parent.kind)) fail("PARENT");
    if (options.invariant || options.virtual) fail("PROFILE");
    if (parent.kind==="map" && fields.length) fail("MAP_STORAGE");
    const f=new Map(parent.fields), m=new Map(parent.methods);
    for(const [name,type] of fields) {
      if(f.has(name)||m.has(name)) fail("FIELD_COLLISION");
      f.set(name,type);
    }
    for(const name of methods) {
      if(m.has(name)||f.has(name)) fail("MEMBER_COLLISION");
      // An explicit non-overridable baseline; production overloads are separate.
      m.set(name,Symbol(name));
    }
    return Object.freeze({kind:parent.kind,id,parent,kv:parent.kv,fields:f,methods:m});
  }
  function nominalUpcast(from,to) {
    const seen=new Set();
    for(let cur=from;cur;cur=cur.parent) {
      if(seen.has(cur)) fail("CYCLE");
      seen.add(cur);
      if(cur===to) return true;
    }
    return false;
  }
  function view(from,to,{mutable=false,exclusive=false}={}) {
    if(from.kind!==to.kind) return false;
    if(mutable&&!exclusive) return false;
    if(from.kind==="map") return nominalUpcast(from,to) && from.kv.every((x,i)=>x===to.kv[i]);
    if(!nominalUpcast(from,to)) return false;
    return !mutable || from.fields.size===to.fields.size;
  }
  function structuralView(from, shape, mutable=false) {
    if(from.kind!=="record") return false;
    for(const [name,type] of shape) if(from.fields.get(name)!==type) return false;
    // Width-changing mutable projections deferred in the bounded model.
    return !mutable || from.fields.size===shape.size;
  }
  function lookup(receiver,name) {
    if(!receiver.methods.has(name)) fail("NO_METHOD");
    return receiver.methods.get(name);
  }
  function literal(type,entries) {
    const seen=new Set();
    for(const [key,t] of entries) {
      if(seen.has(key)) fail("DUPLICATE");
      seen.add(key);
      if(type.kind==="record" && type.fields.get(key)!==t) fail("FIELD_TYPE");
      if(type.kind==="map" && (typeof key!=="string"||t!==type.kv[1])) fail("ENTRY_TYPE");
    }
    if(type.kind==="record" && seen.size!==type.fields.size) fail("MISSING_FIELD");
    return type.id;
  }
  const scores=derive(mapRoot,Symbol("Scores"),[],["passed"]);
  const other=derive(mapRoot,Symbol("Scores"),[],["passed"]);
  const child=derive(scores,Symbol("Child"),[],["extra"]);
  const person=derive(recordRoot,Symbol("Person"),[["name","string"],["age","int"]],["is_adult"]);
  const employee=derive(person,Symbol("Employee"),[["department","string"]],["annual_salary"]);
  const cases=[];
  function check(name,f) { if(f()!==true) fail("ASSERT "+name); cases.push(name); }
  function rejects(code,f) { try { f(); } catch(e) { return e.message===code; } return false; }
  check("nominal identities even with same spelling",()=>scores.id!==other.id);
  check("static inherited map method",()=>lookup(child,"get")===lookup(mapRoot,"get"));
  check("static inherited custom method",()=>lookup(child,"passed")===lookup(scores,"passed"));
  check("base does not gain derived methods",()=>rejects("NO_METHOD",()=>lookup(mapRoot,"passed")));
  check("sibling is not subtype",()=>!nominalUpcast(other,scores));
  check("implicit downcast denied",()=>!nominalUpcast(mapRoot,scores));
  check("readonly base map view",()=>view(scores,mapRoot));
  check("exclusive mutable base view",()=>view(scores,mapRoot,{mutable:true,exclusive:true}));
  check("nonexclusive mutable base view denied",()=>!view(scores,mapRoot,{mutable:true}));
  check("map storage additions denied",()=>rejects("MAP_STORAGE",()=>derive(scores,Symbol(),[["x","int"]])));
  check("builtin slot collision denied",()=>rejects("MEMBER_COLLISION",()=>derive(scores,Symbol(),[],["put"])));
  check("custom invariants deferred",()=>rejects("PROFILE",()=>derive(scores,Symbol(),[],[],{invariant:true})));
  check("virtual dispatch deferred",()=>rejects("PROFILE",()=>derive(scores,Symbol(),[],[],{virtual:true})));
  check("contextual map literal identity",()=>literal(scores,[["alice","int"]])===scores.id);
  check("map value mismatch rejected",()=>rejects("ENTRY_TYPE",()=>literal(scores,[["alice","string"]])));
  check("record inherited method",()=>lookup(employee,"is_adult")===lookup(person,"is_adult"));
  check("record inherited logical order",()=>[...employee.fields.keys()].join(",")==="name,age,department");
  check("record field collision",()=>rejects("FIELD_COLLISION",()=>derive(person,Symbol(),[["age","int"]])));
  check("record methods not fields",()=>!employee.fields.has("is_adult"));
  check("record contextual identity",()=>literal(employee,[["age","int"],["name","string"],["department","string"]])===employee.id);
  check("record literal wrong type",()=>rejects("FIELD_TYPE",()=>literal(person,[["name","string"],["age","bool"]])));
  check("record literal missing field",()=>rejects("MISSING_FIELD",()=>literal(person,[["age","int"]])));
  check("record duplicate field",()=>rejects("DUPLICATE",()=>literal(person,[["age","int"],["age","int"]])));
  check("record readonly width view",()=>view(employee,person));
  check("record mutable width view denied",()=>!view(employee,person,{mutable:true,exclusive:true}));
  check("structural compatible read",()=>structuralView(employee,new Map([["age","int"]])));
  check("structural mismatched field denied",()=>!structuralView(employee,new Map([["age","string"]])));
  check("structural mutable width denied",()=>!structuralView(employee,new Map([["age","int"]]),true));
  return cases;
}
const cases=referenceTests();
for(const name of cases) console.log('PASS '+name);
console.log('HOST_MODEL_ONLY: '+cases.length+' checks passed; guest evidence NOT_EXECUTED');
