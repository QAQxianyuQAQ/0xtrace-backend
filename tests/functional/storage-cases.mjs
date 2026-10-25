import assert from 'node:assert/strict'
import {readFile,writeFile}from'node:fs/promises'
import {createHash}from'node:crypto'
import {setTimeout as sleep}from'node:timers/promises'
if(process.env.TEST_PROJECT!=='0xtrace-functional-20261025')throw Error('仅限隔离合成环境')
const origin='https://web:8443',cfg=Object.fromEntries((await readFile('/work/private/infra.env','utf8')).trim().split('\n').map(s=>{const i=s.indexOf('=');return[s.slice(0,i),s.slice(i+1)]}));let cookie='',last=0;const results=[]
async function req(path,{method='GET',body,status=200}={}){if(method!=='GET'){await sleep(Math.max(0,1300-(Date.now()-last)));last=Date.now()}const r=await fetch(origin+'/api/v1'+path,{method,redirect:'manual',signal:AbortSignal.timeout(60000),headers:{Origin:origin,...(cookie?{Cookie:cookie}:{}),...(body&&!(body instanceof FormData)?{'Content-Type':'application/json'}:{})},body:body instanceof FormData?body:body?JSON.stringify(body):undefined}),bytes=Buffer.from(await r.arrayBuffer());let data;try{data=JSON.parse(bytes.toString())}catch{}assert.equal(r.status,status,method+' '+path+' HTTP '+r.status+' '+data?.error?.code);if(path==='/auth/login')cookie=r.headers.get('set-cookie').split(';')[0];return{data,bytes,headers:r.headers}}
async function test(name,fn){try{await fn();results.push({name,status:'PASS'})}catch(e){results.push({name,status:'FAIL',detail:e.message})}console.log(JSON.stringify(results.at(-1)));await writeFile('/out/storage-'+process.argv[2]+'.json',JSON.stringify(results,null,2))}
const form=entries=>{const f=new FormData();for(const[field,name,bytes,type]of entries)f.append(field,new Blob([bytes],{type:type||'text/plain'}),name);return f},sha=b=>createHash('sha256').update(b).digest('hex')
await req('/auth/login',{method:'POST',body:{username:cfg.TQ_ADMIN_USERNAME,password:cfg.TQ_ADMIN_PASSWORD}})
let context;const file='/work/private/storage-fault.json'
if(process.argv[2]==='prepare'){
 const code='FAULT-'+Date.now().toString(36).toUpperCase();await req('/admin/challenges',{method:'POST',status:201,body:{code,title:'仅隔离故障测试',category:'CTF',difficulty:'测试',points:100,answer:'fault',status:'published'}})
 const coverBytes=await readFile('/media/test-cover.jpg'),cover=(await req('/admin/challenges/'+code+'/cover',{method:'POST',status:201,body:form([['file','initial.jpg',coverBytes,'image/jpeg']])})).data
 const attachment=(await req('/admin/challenges/'+code+'/attachments',{method:'POST',status:201,body:form([['files','keep.txt','keep-before-failure']])})).data.items[0]
 context={code,cover,attachment,coverHash:sha(coverBytes)};await writeFile(file,JSON.stringify(context),{mode:0o600})
 await test('过长文件名与批量预校验在写 S3 前受控400',async()=>{for(const entries of [[['files','a'.repeat(270)+'.txt','long']],[['files','ok.txt','ok'],['files','b'.repeat(270)+'.txt','long']]])await req('/admin/challenges/'+code+'/attachments',{method:'POST',status:400,body:form(entries)});assert.deepEqual((await req('/admin/challenges/'+code+'/attachments')).data.map(x=>x.id),[attachment.id])})
 await test('假 MIME 和损坏真实图片都拒绝',async()=>{for(const bytes of [Buffer.from('not a PNG'),Buffer.from([137,80,78,71,0,0,0,0,0,0,0,0,0,0,0,0]),coverBytes.subarray(0,40)])await req('/admin/challenges/'+code+'/cover',{method:'POST',status:422,body:form([['file','bad.png',bytes,'image/png']])});assert.equal((await req('/admin/challenges/'+code+'/cover')).data.id,cover.id)})
}else{
 context=JSON.parse(await readFile(file,'utf8'));const{code,cover,attachment}=context
 if(process.argv[2]==='db-fault'){
  await test('真实数据库插入失败时批量元数据整体回滚',async()=>{await req('/admin/challenges/'+code+'/attachments',{method:'POST',status:500,body:form([['files','new-before-failure.txt','rollback me'],['files','TQ-FAIL.txt','reject DB insert']])});assert.deepEqual((await req('/admin/challenges/'+code+'/attachments')).data.map(x=>x.id),[attachment.id])})
  await test('替换封面入库失败时旧封面与字节仍完好',async()=>{await req('/admin/challenges/'+code+'/cover',{method:'POST',status:500,body:form([['file','TQ-FAIL.jpg',await readFile('/media/test-cover.jpg'),'image/jpeg']])});assert.equal((await req('/admin/challenges/'+code+'/cover')).data.id,cover.id);assert.equal(sha((await req('/files/'+cover.id)).bytes),context.coverHash)})
 }else if(process.argv[2]==='offline'){
  await test('真实 MinIO 停机时删除失败且元数据保留',async()=>{await req('/admin/challenges/'+code+'/attachments/'+attachment.id,{method:'DELETE',status:500});assert.ok((await req('/admin/challenges/'+code+'/attachments')).data.some(x=>x.id===attachment.id))})
  await test('真实 MinIO 停机时上传失败不登记元数据',async()=>{await req('/admin/challenges/'+code+'/attachments',{method:'POST',status:500,body:form([['files','offline.txt','not stored']])});assert.deepEqual((await req('/admin/challenges/'+code+'/attachments')).data.map(x=>x.id),[attachment.id])})
 }else if(process.argv[2]==='recovery'){
  // readiness不等于鉴权对象链路已恢复；只对只读探针作30秒有界等待，不重试删除。
  const start=Date.now();let ready=false;
  while(Date.now()-start<30000) {
   try {
    const response=await fetch(origin+'/api/v1/files/'+attachment.id,{headers:{Cookie:cookie,Origin:origin},signal:AbortSignal.timeout(3000)});
    await response.arrayBuffer();
    if(response.status===200){ready=true;break}
    if(response.status<500)throw new Error('恢复探针永久错误 '+response.status)
   }catch(error){if(error.message.startsWith('恢复探针永久错误'))throw error}
   await sleep(500)
  }
  assert.ok(ready,'对象链路未在30秒内恢复');console.log('只读恢复等待 '+(Date.now()-start)+'ms');
  await test('恢复后原附件、封面可下载且字节不变',async()=>{assert.equal((await req('/files/'+attachment.id)).bytes.toString(),'keep-before-failure');assert.equal(sha((await req('/files/'+cover.id)).bytes),context.coverHash)})
  await test('恢复后可重试删除并清空封面引用',async()=>{await req('/admin/challenges/'+code+'/attachments/'+attachment.id,{method:'DELETE',status:204});await req('/admin/challenges/'+code+'/cover',{method:'DELETE',status:204});assert.ok(!(await req('/challenges/'+code)).data.coverUrl);await req('/files/'+attachment.id,{status:404});await req('/files/'+cover.id,{status:404})})
 }
}
process.exitCode=results.some(x=>x.status==='FAIL')?1:0
