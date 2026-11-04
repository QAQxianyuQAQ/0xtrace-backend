"use strict";
(() => {
  const ns = "http://www.w3.org/2000/svg";
  const stage = document.getElementById("stage"), status = document.getElementById("status");
  const result = document.getElementById("result"), reset = document.getElementById("reset");
  const rotate = document.getElementById("rotate"), flip = document.getElementById("flip");
  const returnButton = document.getElementById("return");
  let puzzle, pieces = [], selected = null, drag = null, busy = false, won = false;
  let token, revision, origins, unit, sourceUnit, scrollFrame;
  function el(name, attrs = {}, text) {
    const node = document.createElementNS(ns, name);
    for (const [k,v] of Object.entries(attrs)) node.setAttribute(k,v);
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function shape(piece) {
    let cells = piece.cells.map(([x,y]) => [piece.flipped ? -x : x,y]);
    for (let r=0;r<piece.rotation;r++) cells = cells.map(([x,y])=>[-y,x]);
    const x0=Math.min(...cells.map(p=>p[0])), y0=Math.min(...cells.map(p=>p[1]));
    return cells.map(([x,y])=>[x-x0,y-y0]);
  }
  function bounds(piece) {
    const cells=shape(piece);
    return {w:1+Math.max(...cells.map(p=>p[0])),h:1+Math.max(...cells.map(p=>p[1]))};
  }
  function position(piece) {
    if(piece.zone==='target')return {x:origins.target.x+piece.x*unit,y:origins.target.y+piece.y*unit,scale:unit};
    const index=pieces.indexOf(piece), b=bounds(piece);
    return {x:origins.source.x+(index%4)*100+(100-b.w*sourceUnit)/2,
            y:origins.source.y+Math.floor(index/4)*116+(100-b.h*sourceUnit)/2,scale:sourceUnit};
  }
  function point(event) {
    return new DOMPoint(event.clientX,event.clientY).matrixTransform(stage.getScreenCTM().inverse());
  }
  function locate(piece) {
    const p=position(piece);
    piece.node.setAttribute('transform',`translate(${p.x} ${p.y}) scale(${p.scale})`);
  }
  function pieceDrawing(piece) {
    const group=el('g',{class:'piece','data-id':piece.id,tabindex:0,role:'button','aria-label':`拼片 ${piece.id}`});
    const cells=shape(piece), keys=new Set(cells.map(p=>p.join(',')));
    for(const [x,y] of cells) {
      group.append(el('rect',{x,y,width:1,height:1,fill:piece.color,class:'piece-cell'}));
      for(const [dx,dy,x1,y1,x2,y2] of [[-1,0,x,y,x,y+1],[1,0,x+1,y,x+1,y+1],[0,-1,x,y,x+1,y],[0,1,x,y+1,x+1,y+1]]) {
        if(!keys.has(`${x+dx},${y+dy}`))group.append(el('line',{x1,y1,x2,y2,class:'piece-outline'}));
      }
    }
    const [lx,ly]=cells[Math.floor(cells.length/2)];
    group.append(el('text',{x:lx+.5,y:ly+.52,class:'piece-number'},piece.id));
    group.addEventListener('pointerdown',e=>beginDrag(e,piece));
    group.addEventListener('focus',()=>select(piece.id));
    group.addEventListener('keydown',e=>keyboard(e,piece));
    group.classList.toggle('selected',piece.id===selected);
    piece.node=group;
    return group;
  }
  function layout() {
    if(!puzzle)return;
    const focused=document.activeElement?.dataset?.id;
    if(drag)finishDrag(null,true);
    const small=matchMedia('(max-width:759px)').matches;
    unit=small?36:48;sourceUnit=small?16:18;
    origins={source:{x:small?0:28,y:50},target:{x:small?20:504,y:small?350:80}};
    stage.setAttribute('viewBox',small?'0 0 400 596':'0 0 1010 420');
    stage.replaceChildren();
    stage.append(el('text',{x:origins.source.x+16,y:28,class:'board-label'},'拼片 / 08'));
    for(let i=0;i<8;i++)stage.append(el('rect',{x:origins.source.x+i%4*100+3,y:origins.source.y+Math.floor(i/4)*116-3,width:94,height:106,rx:8,class:'tray-slot'}));
    const o=origins.target, board=puzzle.board;
    stage.append(el('text',{x:o.x,y:o.y-24,class:'board-label'},`${String(puzzle.stage).padStart(2,'0')} / ${board.name}`));
    const target=el('g',{'data-board':'target',transform:`translate(${o.x} ${o.y}) scale(${unit})`});
    const keys=new Set(board.cells.map(p=>p.join(',')));
    for(const [x,y] of board.cells) {
      target.append(el('rect',{x,y,width:1,height:1,class:'board-cell'}));
      for(const [dx,dy,x1,y1,x2,y2] of [[-1,0,x,y,x,y+1],[1,0,x+1,y,x+1,y+1],[0,-1,x,y,x+1,y],[0,1,x,y+1,x+1,y+1]])
        if(!keys.has(`${x+dx},${y+dy}`))target.append(el('line',{x1,y1,x2,y2,class:'board-edge'}));
    }
    stage.append(target);
    for(const piece of pieces){stage.append(pieceDrawing(piece));locate(piece);}
    if(focused&&focused===selected)pieces.find(p=>p.id===focused)?.node.focus({preventScroll:true});
    controls();
  }
  function controls() {
    reset.disabled=busy||!puzzle;
    for(const button of [rotate,flip,returnButton])button.disabled=busy||won||!selected;
    document.getElementById('selection').textContent=selected?`拼片 ${selected}`:'选择拼片';
  }
  function select(id) {
    selected=id;
    for(const p of pieces)if(p.node)p.node.classList.toggle('selected',p.id===id);
    controls();
  }
  function payload(piece) {
    return {id:piece.id,zone:piece.zone,x:piece.x,y:piece.y,rotation:piece.rotation,flipped:piece.flipped};
  }
  function legal(piece) {
    if(piece.zone==='source')return true;
    const target=new Set(puzzle.board.cells.map(p=>p.join(','))), occupied=new Set();
    for(const p of pieces)if(p.id!==piece.id&&p.zone==='target')
      for(const [x,y] of shape(p))occupied.add(`${p.x+x},${p.y+y}`);
    return shape(piece).every(([x,y])=>target.has(`${x+piece.x},${y+piece.y}`)&&!occupied.has(`${x+piece.x},${y+piece.y}`));
  }
  function beginDrag(event,piece) {
    if(busy||won||drag||(event.pointerType==='mouse'&&event.button!==0))return;
    event.preventDefault();select(piece.id);piece.node.focus({preventScroll:true});
    const p=point(event),pos=position(piece);
    drag={piece,pointer:event.pointerId,before:payload(piece),startX:event.clientX,startY:event.clientY,
          clientX:event.clientX,clientY:event.clientY,grabX:(p.x-pos.x)/pos.scale,grabY:(p.y-pos.y)/pos.scale,moved:false};
    stage.append(piece.node);piece.node.classList.add('dragging');
    stage.setPointerCapture(event.pointerId);scrollFrame=requestAnimationFrame(autoScroll);
  }
  function updateDrag() {
    if(!drag)return;
    if(Math.hypot(drag.clientX-drag.startX,drag.clientY-drag.startY)>4)drag.moved=true;
    if(!drag.moved)return;
    const p=point(drag);
    drag.wx=p.x-drag.grabX*unit;drag.wy=p.y-drag.grabY*unit;
    drag.piece.node.setAttribute('transform',`translate(${drag.wx} ${drag.wy}) scale(${unit})`);
  }
  function autoScroll() {
    if(!drag)return;
    if(drag.moved) {
      const dy=drag.clientY>innerHeight-55?10:drag.clientY<55?-10:0;
      if(dy){window.scrollBy(0,dy);updateDrag();}
    }
    scrollFrame=requestAnimationFrame(autoScroll);
  }
  function finishDrag(event,cancel=false) {
    if(!drag||(event&&event.pointerId!==drag.pointer))return;
    if(event&&!cancel){drag.clientX=event.clientX;drag.clientY=event.clientY;updateDrag();}
    const d=drag;drag=null;cancelAnimationFrame(scrollFrame);
    if(stage.hasPointerCapture(d.pointer))stage.releasePointerCapture(d.pointer);
    d.piece.node.classList.remove('dragging');
    if(cancel||!d.moved){locate(d.piece);return;}
    const p=point(d),s=origins.source;
    if(p.x>=s.x&&p.x<=s.x+400&&p.y>=s.y-10&&p.y<=s.y+232)Object.assign(d.piece,{zone:'source',x:0,y:0});
    else Object.assign(d.piece,{zone:'target',x:Math.round((d.wx-origins.target.x)/unit),y:Math.round((d.wy-origins.target.y)/unit)});
    if(!legal(d.piece)) {
      Object.assign(d.piece,d.before);locate(d.piece);status.textContent='放置无效，请对齐空格';return;
    }
    locate(d.piece);submit('move',payload(d.piece));
  }
  function transform(kind) {
    if(busy||won||drag||!selected)return;
    const p=pieces.find(p=>p.id===selected);
    if(kind==='rotate')p.rotation=(p.rotation+1)%4;
    if(kind==='flip')p.flipped=!p.flipped;
    if(kind==='return'||!legal(p))Object.assign(p,{zone:'source',x:0,y:0});
    layout();submit('move',payload(p));
  }
  function keyboard(event,piece) {
    if(busy||won||drag)return;
    if(event.key.toLowerCase()==='r'||event.key.toLowerCase()==='f') {
      event.preventDefault();select(piece.id);transform(event.key.toLowerCase()==='r'?'rotate':'flip');return;
    }
    const dirs={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]};
    const before=payload(piece);
    if(dirs[event.key]) {
      event.preventDefault();if(piece.zone==='source')return;
      piece.x+=dirs[event.key][0];piece.y+=dirs[event.key][1];
    }else if(event.key==='Enter'||event.key===' ') {
      event.preventDefault();piece.zone=piece.zone==='source'?'target':'source';piece.x=0;piece.y=0;
    }else return;
    if(!legal(piece)){Object.assign(piece,before);return;}
    locate(piece);submit('move',payload(piece));
  }
  function install(data) {
    if(drag)finishDrag(null,true);
    const oldStage=puzzle?.stage;
    puzzle={...puzzle,...data};token=data.token;revision=data.revision;won=data.solved;
    pieces=puzzle.pieces.map(p=>({...p,...data.placements.find(s=>s.id===p.id)}));
    if(oldStage!==data.stage)selected=null;
    result.hidden=!data.flag;document.getElementById('flag').textContent=data.flag||'';
    document.getElementById('copy').textContent='复制';
    status.classList.toggle('done',won);
    status.textContent=won?'两关已完成':data.advanced?'第一关完成 · 第二关 / 钥匙':`第 ${data.stage} 关 / ${data.board.name}`;
    layout();
    if(data.flag)result.scrollIntoView({behavior:'smooth',block:'nearest'});
  }
  async function submit(action,move) {
    if(busy)return;
    busy=true;controls();
    try {
      const body={token,revision};if(move)body.move=move;
      const response=await fetch(`/api/${action}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
      if(!response.ok)throw new Error('sync');
      install(await response.json());
    }catch{
      try {
        const response=await fetch('/api/puzzle');if(!response.ok)throw new Error();
        install(await response.json());status.textContent='状态已同步，请继续';
      }catch{status.textContent='连接失败，请刷新';}
    }finally{busy=false;controls();}
  }
  stage.addEventListener('pointermove',e=>{if(drag&&e.pointerId===drag.pointer){drag.clientX=e.clientX;drag.clientY=e.clientY;updateDrag();}});
  stage.addEventListener('pointerup',e=>finishDrag(e));
  stage.addEventListener('pointercancel',e=>finishDrag(e,true));
  stage.addEventListener('lostpointercapture',e=>{if(drag&&e.pointerId===drag.pointer)finishDrag(e,true);});
  rotate.addEventListener('click',()=>transform('rotate'));flip.addEventListener('click',()=>transform('flip'));
  returnButton.addEventListener('click',()=>transform('return'));
  reset.addEventListener('click',()=>{if(drag)finishDrag(null,true);submit('reset');});
  window.addEventListener('resize',layout);
  document.getElementById('copy').addEventListener('click',async()=>{
    try{await navigator.clipboard.writeText(document.getElementById('flag').textContent);document.getElementById('copy').textContent='已复制';}
    catch{const range=document.createRange();range.selectNodeContents(document.getElementById('flag'));const selection=getSelection();selection.removeAllRanges();selection.addRange(range);document.getElementById('copy').textContent='已选中';}
  });
  fetch('/api/puzzle').then(r=>{if(!r.ok)throw new Error();return r.json();}).then(data=>{
    install(data);if(data.solved)submit('check');
  }).catch(()=>{status.textContent='加载失败，请刷新';});
})();
