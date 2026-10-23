#!/usr/bin/env python3
"""Private actual managed-payment automatic worker qualification.

The accepted financial cores remain immutable inputs. The real domain uses native CQRS,
per-command Jafly SagaEngine and genuine generated payment/ledger SDKs. Only fresh private
PostgreSQL containers, synthetic identities and random loopback ports are used. The experience
calls only its genuine domain SDK boundary. External rails, settlement and production identity
provider qualification remain pending separate deliveries.
"""
import argparse
import base64
import urllib.parse
import concurrent.futures
import contextlib
import datetime
import hashlib
import http.server
import importlib.util
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from ManagedPaymentFaultGateway import LedgerFaultGateway

SCOPES=['payments:create','payments:read','payments:coordinate','payments:confirm','payments:cancel','payments:recover',
        'funds:read','funds:reserve','funds:commit','funds:release','funds:reconcile','funds:expire']
BASE='/api/v1/tenants/owned/internal-payments'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for service in ['accounts','ledger','payments','domain','experience']:
        parser.add_argument('--'+service+'-jar',type=pathlib.Path,required=True)
    parser.add_argument('--domain-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--experience-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--payments-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--ledger-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--sdk-probe-source',type=pathlib.Path,default=pathlib.Path(__file__).with_name('ManagedPaymentExperienceSdkProbe.java'))
    parser.add_argument('--funds-fixture-script',type=pathlib.Path,required=True)
    parser.add_argument('--output-dir',type=pathlib.Path,required=True)
    parser.add_argument('--java',default=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else shutil.which('java'))
    parser.add_argument('--docker-host',default=os.environ.get('DOCKER_HOST'))
    args=parser.parse_args()
    if not args.java or not shutil.which('docker'):parser.error('JDK25 and Docker are required')
    for path in [args.accounts_jar,args.ledger_jar,args.payments_jar,args.domain_jar,args.experience_jar,args.experience_sdk,args.domain_sdk,args.payments_sdk,args.ledger_sdk,args.sdk_probe_source,args.funds_fixture_script]:
        if not path.is_file():parser.error('Missing owned input: '+str(path))
    spec=importlib.util.spec_from_file_location('owned_funds_fixture',args.funds_fixture_script)
    f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
    f.ROOT=args.output_dir.resolve();f.ROOT.mkdir(parents=True,exist_ok=True);(f.ROOT/'reports').mkdir(exist_ok=True)
    f.JAVA=args.java;f.DOCKER=[shutil.which('docker')]+(['--host',args.docker_host] if args.docker_host else [])
    f.CONTAINERS={};f.DB_PORTS={};f.JARS={};f.KEY=None
    f.BOOTSTRAP_PASSWORD=secrets.token_urlsafe(24);f.RUNTIME_PASSWORD=secrets.token_urlsafe(24);f.CLIENT_SECRET=secrets.token_urlsafe(24)
    report={'status':'FAILED','scope':'actual five-layer native OAuth/scheduler/queue recovery with copied financial cores and crash/lease/deadline/grant/backoff/exhaustion qualification','artifacts':{},'qualifier_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(args.funds_fixture_script.read_bytes()).hexdigest()}
    report['fault_gateway_sha256']=hashlib.sha256(pathlib.Path(__file__).with_name('ManagedPaymentFaultGateway.py').read_bytes()).hexdigest()
    source_inputs={'qualifier':pathlib.Path(__file__),'fault_gateway':pathlib.Path(__file__).with_name('ManagedPaymentFaultGateway.py'),'sdk_probe':args.sdk_probe_source,'funds_fixture':args.funds_fixture_script}
    (f.ROOT/'qualifier-inputs').mkdir(exist_ok=True)
    report['qualifier_inputs']={}
    for name,path in source_inputs.items():
        copied=f.ROOT/'qualifier-inputs'/(name+path.suffix);shutil.copy2(path,copied)
        report['qualifier_inputs'][name]={'path':str(path.resolve()),'sha256':hashlib.sha256(copied.read_bytes()).hexdigest(),'copy':str(copied)}
    fixture=None;gateway=None;temporary_directory=None
    def domain(method,path='',body=None,accounts=(),expected=None,scopes=None):
        token=f.token(['experience-managed','domain-managed','payments-managed','ledger-funds'],accounts,SCOPES if scopes is None else scopes)
        request=urllib.request.Request('http://127.0.0.1:'+str(f.PEER_PORTS['experience'])+BASE+path,data=None if body is None else json.dumps(body).encode(),method=method,headers={'Content-Type':'application/json','Authorization':'Bearer '+token,'Idempotency-Key':str(body.get('actionId',uuid.uuid4())) if body else str(uuid.uuid4())})
        try:
            with urllib.request.urlopen(request,timeout=30) as response:status,raw=response.status,response.read()
        except urllib.error.HTTPError as error:status,raw=error.code,error.read()
        value=json.loads(raw) if raw else None
        if expected is not None:f.check(status==expected,method+' actual experience '+path+' status='+str(expected),(status,value))
        return status,value
    def boot_payments(replica='payments'):
        log=(f.ROOT/(replica+'-boot-'+str(time.time_ns())+'.log')).open('w');f.LOG_FILES.append(str(log.name))
        env=dict(os.environ,DB_HOST='127.0.0.1',DB_PORT=str(f.DB_PORTS['payments']),DB_NAME='fnd_payments',
            DB_USERNAME='funds_runtime',DB_PASSWORD=f.RUNTIME_PASSWORD,DB_SSL_MODE='disable',SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(f.PEER_PORTS[replica]),
            SPRING_FLYWAY_USER='postgres',SPRING_FLYWAY_PASSWORD=f.BOOTSTRAP_PASSWORD,FIREFLY_EDA_ENABLED='false',
            MANAGED_PAYMENTS_SECURITY_ISSUER=f.ISSUER,MANAGED_PAYMENTS_SECURITY_AUDIENCE='payments-managed',MANAGED_PAYMENTS_SECURITY_JWK_SET_URI=f.FIXTURE_URL+'/jwks',
            MANAGED_PAYMENTS_ALLOW_LOOPBACK_HTTP='true',MANAGED_PAYMENTS_DELEGATION_MODE='validated-multi-audience',MANAGED_PAYMENTS_CLAIM_LEASE_SECONDS='15',
            MANAGED_PAYMENTS_PEERS_LEDGER_BASE_URL='http://127.0.0.1:'+str(f.PEER_PORTS['ledger']),MANAGED_PAYMENTS_PEERS_LEDGER_AUDIENCE='ledger-funds',
            LOGGING_LEVEL_ROOT='WARN',LOGGING_LEVEL_IO_FIREFLYSOFTWARE='INFO',LOGGING_LEVEL_ORG_FIREFLYFRAMEWORK='WARN')
        process=subprocess.Popen([f.JAVA,'-jar',str(f.JARS['payments'])],env=env,stdout=log,stderr=subprocess.STDOUT);f.PROCESSES[replica]=(process,log)
        end=time.monotonic()+90
        while time.monotonic()<end:
            if process.poll() is not None:raise RuntimeError('payments exited; inspect '+str(log.name))
            try:urllib.request.urlopen('http://127.0.0.1:'+str(f.PEER_PORTS[replica])+'/v3/api-docs',timeout=1).close();return
            except (OSError,urllib.error.HTTPError):time.sleep(.2)
        raise RuntimeError('owned payments startup timeout')
    def boot_domain(worker=False, replica="domain", client="worker-one"):
        f.PEER_PORTS.setdefault(replica,f.free_port())
        log=(f.ROOT/(replica+'-boot-'+str(time.time_ns())+'.log')).open('w');f.LOG_FILES.append(str(log.name))
        env=dict(os.environ,SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(f.PEER_PORTS[replica]),FIREFLY_EDA_ENABLED='false',FIREFLY_STEPEVENTS_ENABLED='false',
            MANAGED_PAYMENTS_DOMAIN_ISSUER=f.ISSUER,MANAGED_PAYMENTS_DOMAIN_AUDIENCE='domain-managed',MANAGED_PAYMENTS_DOMAIN_JWKS_URI=f.FIXTURE_URL+'/jwks',
            MANAGED_PAYMENTS_DOMAIN_ALLOW_LOOPBACK_HTTP='true',MANAGED_PAYMENTS_DOMAIN_DELEGATION_MODE='validated-multi-audience',
            MANAGED_PAYMENTS_DOMAIN_CORE_URL='http://127.0.0.1:'+str(f.PEER_PORTS['payments']),MANAGED_PAYMENTS_DOMAIN_CORE_AUDIENCE='payments-managed',
            MANAGED_PAYMENTS_DOMAIN_LEDGER_URL=gateway.url if gateway else 'http://127.0.0.1:'+str(f.PEER_PORTS['ledger']),MANAGED_PAYMENTS_DOMAIN_LEDGER_AUDIENCE='ledger-funds',
            LOGGING_LEVEL_ROOT='WARN',LOGGING_LEVEL_IO_FIREFLYSOFTWARE='INFO',LOGGING_LEVEL_ORG_FIREFLYFRAMEWORK='WARN')
        if worker:
            native={'spring':{'security':{'oauth2':{'client':{
              'registration':{'owned-worker':{'provider':'owned-worker','client-id':client,'client-secret':f.CLIENT_SECRET,'authorization-grant-type':'client_credentials'}},
              'provider':{'owned-worker':{'token-uri':f.FIXTURE_URL+'/worker-token'}}}}}},
              'managed-payments':{'recovery':{'enabled':True,'actor-mode':'oauth2-client-credentials','interval-seconds':1,'batch-size':25,'concurrency':2,'registrations':{'owned':'owned-worker'}}}}
            env['SPRING_APPLICATION_JSON']=json.dumps(native)
        process=subprocess.Popen([f.JAVA,'-jar',str(f.JARS['domain'])],env=env,stdout=log,stderr=subprocess.STDOUT);f.PROCESSES[replica]=(process,log)
        end=time.monotonic()+90
        while time.monotonic()<end:
            if process.poll() is not None:raise RuntimeError('domain exited; inspect '+str(log.name))
            try:urllib.request.urlopen('http://127.0.0.1:'+str(f.PEER_PORTS[replica])+'/v3/api-docs',timeout=1).close();return
            except (OSError,urllib.error.HTTPError):time.sleep(.2)
        raise RuntimeError('owned domain startup timeout')
    def boot_experience():
        f.PEER_PORTS.setdefault('experience',f.free_port())
        log=(f.ROOT/('experience-boot-'+str(time.time_ns())+'.log')).open('w');f.LOG_FILES.append(str(log.name))
        env=dict(os.environ,SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(f.PEER_PORTS['experience']),FIREFLY_EDA_ENABLED='false',FIREFLY_STEPEVENTS_ENABLED='false',
            MANAGED_PAYMENTS_EXPERIENCE_ISSUER=f.ISSUER,MANAGED_PAYMENTS_EXPERIENCE_AUDIENCE='experience-managed',MANAGED_PAYMENTS_EXPERIENCE_JWK_SET_URI=f.FIXTURE_URL+'/jwks',
            MANAGED_PAYMENTS_EXPERIENCE_ALLOW_LOOPBACK_HTTP='true',MANAGED_PAYMENTS_EXPERIENCE_DELEGATION_MODE='validated-multi-audience',MANAGED_PAYMENTS_EXPERIENCE_AUTHORIZATION_MODE='signed-resource-grants',
            MANAGED_PAYMENTS_EXPERIENCE_DOMAIN_URL='http://127.0.0.1:'+str(f.PEER_PORTS['domain']),MANAGED_PAYMENTS_EXPERIENCE_DOMAIN_AUDIENCE='domain-managed',
            LOGGING_LEVEL_ROOT='WARN',LOGGING_LEVEL_IO_FIREFLYSOFTWARE='INFO',LOGGING_LEVEL_ORG_FIREFLYFRAMEWORK='WARN')
        process=subprocess.Popen([f.JAVA,'-jar',str(f.JARS['experience'])],env=env,stdout=log,stderr=subprocess.STDOUT);f.PROCESSES['experience']=(process,log)
        end=time.monotonic()+90
        while time.monotonic()<end:
            if process.poll() is not None:raise RuntimeError('experience exited; inspect '+str(log.name))
            try:urllib.request.urlopen('http://127.0.0.1:'+str(f.PEER_PORTS['experience'])+'/v3/api-docs',timeout=1).close();return
            except (OSError,urllib.error.HTTPError):time.sleep(.2)
        raise RuntimeError('owned experience startup timeout')
    worker_authority={'alternate_denial':False,'issued':0};worker_accounts=[];worker_tokens=[];worker_scopes=[x for x in SCOPES if x not in ['payments:create','payments:confirm','payments:cancel']]
    class WorkerFixture(f.Fixture):
        def do_POST(self):
            if self.path!='/worker-token':return super().do_POST()
            raw=self.rfile.read(int(self.headers.get('Content-Length',0)))
            auth=self.headers.get('Authorization','');client=secret=''
            if auth.startswith('Basic '):client,secret=base64.b64decode(auth[6:]).decode().split(':',1)
            form=urllib.parse.parse_qs(raw.decode())
            if client not in ['worker-one','worker-two'] or secret!=f.CLIENT_SECRET or form.get('grant_type')!=['client_credentials']:return self.send_json(401,{'error':'invalid_client'})
            issued_scopes=list(worker_scopes)
            if worker_authority['alternate_denial']:
                if worker_authority['issued']%2==1:issued_scopes.remove('payments:recover')
                worker_authority['issued']+=1
            now=int(time.time());claims={'iss':f.ISSUER,'aud':['domain-managed','payments-managed','ledger-funds'],'sub':'synthetic-service-'+client,'tenant_id':'owned','scope':' '.join(issued_scopes),'funds_accounts':list(worker_accounts),'client_id':client,'iat':now,'exp':now+30}
            value=f.b64(json.dumps({'alg':'RS256','kid':'owned-peer'}).encode())+'.'+f.b64(json.dumps(claims).encode());sig=f.run(['openssl','dgst','-sha256','-sign',str(f.KEY)],input=value.encode());signed=value+'.'+f.b64(sig)
            worker_tokens.append({'client':client,'time':time.time(),'scopes':issued_scopes})
            return self.send_json(200,{'token_type':'Bearer','expires_in':30,'access_token':signed})
    def core(method,path='',body=None,expected=None):
        req=urllib.request.Request('http://127.0.0.1:'+str(f.PEER_PORTS['payments'])+BASE+path,data=None if body is None else json.dumps(body).encode(),method=method,headers={'Content-Type':'application/json','Authorization':'Bearer '+f.token(['payments-managed','ledger-funds'],worker_accounts,SCOPES)})
        try:
            with urllib.request.urlopen(req,timeout=20)as response:status,raw=response.status,response.read()
        except urllib.error.HTTPError as error:status,raw=error.code,error.read()
        value=json.loads(raw)if raw else None
        if expected is not None:f.check(status==expected,'worker core '+method+' '+path+' status='+str(expected),(status,value))
        return status,value
    def eventually(payment,state,seconds=30):
        end=time.monotonic()+seconds;last=None
        while time.monotonic()<end:
            status,last=core('GET','/'+payment)
            if status==200 and last['state']==state and last['pendingEffectId']is None:return last
            time.sleep(.2)
        raise AssertionError('Retained worker state not observed: '+state+' '+str(last))
    def effect(payment):
        payment=str(uuid.UUID(payment))
        raw=f.psql(f.CONTAINERS['payments'],"SELECT json_build_object('id',effect_id,'participant',participant_action_id,'kind',kind,'state',state,'attempts',attempts,'fence',fence,'owner',owner_subject,'lease',extract(epoch FROM lease_until),'next',extract(epoch FROM next_attempt_at),'now',extract(epoch FROM clock_timestamp()),'event',extract(epoch FROM (SELECT max(created_at) FROM managed_payment_outbox WHERE tenant_id=e.tenant_id AND payment_id=e.payment_id)),'claimed',extract(epoch FROM (SELECT max(created_at) FROM managed_payment_outbox WHERE tenant_id=e.tenant_id AND payment_id=e.payment_id AND payload->>'type'='EFFECT_CLAIMED')))::text FROM managed_payment_effect e WHERE tenant_id='owned' AND payment_id='"+payment+"' ORDER BY kind;",'fnd_payments')
        rows=[json.loads(line)for line in raw.splitlines()if line]
        return rows
    def wait_effect(payment,predicate,seconds=35):
        end=time.monotonic()+seconds;last=None
        while time.monotonic()<end:
            last=effect(payment)
            if predicate(last):return last
            time.sleep(.05)
        raise AssertionError('Actual retained effect not observed: '+str(last))
    def crash_worker(replica='domain'):
        process,log=f.PROCESSES.pop(replica)
        process.kill();process.wait(5);log.close()
    def stale_ack(payment,old,version):
        body={'effectId':old['id'],'fence':old['fence'],'expectedVersion':version}
        token=f.token(['payments-managed','ledger-funds'],worker_accounts,SCOPES,subject=old['owner'])
        req=urllib.request.Request('http://127.0.0.1:'+str(f.PEER_PORTS['payments'])+BASE+'/'+payment+'/acknowledgments',data=json.dumps(body).encode(),method='POST',headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
        try:
            with urllib.request.urlopen(req,timeout=20)as response:return response.status
        except urllib.error.HTTPError as error:return error.code
    try:
        temporary_directory=tempfile.TemporaryDirectory(prefix='owned-payment-domain-')
        with contextlib.nullcontext(temporary_directory.name) as temporary:
            for service,path in [('accounts',args.accounts_jar),('ledger',args.ledger_jar),('payments',args.payments_jar),('domain',args.domain_jar),('experience',args.experience_jar)]:
                frozen=pathlib.Path(temporary)/(service+'.jar');shutil.copy2(path,frozen);sha=hashlib.sha256(frozen.read_bytes()).hexdigest()
                if sha!=hashlib.sha256(path.read_bytes()).hexdigest():raise RuntimeError('Input changed during copy: '+service)
                f.JARS[service]=frozen;report['artifacts'][service]={'path':str(path.resolve()),'sha256':sha}
            with zipfile.ZipFile(f.JARS['experience']) as archive:
                names=[n for n in archive.namelist() if n.startswith('BOOT-INF/lib/domain-payments-sdk-') and n.endswith('.jar')]
                f.check(len(names)==1 and hashlib.sha256(archive.read(names[0])).hexdigest()==hashlib.sha256(args.domain_sdk.read_bytes()).hexdigest(),'experience embeds exact accepted domain SDK')
            sdk=pathlib.Path(temporary)/'experience-sdk.jar';shutil.copy2(args.experience_sdk,sdk)
            report['artifacts']['domain_sdk']={'path':str(args.domain_sdk.resolve()),'sha256':hashlib.sha256(args.domain_sdk.read_bytes()).hexdigest()}
            report['artifacts']['experience_sdk']={'path':str(args.experience_sdk.resolve()),'sha256':hashlib.sha256(sdk.read_bytes()).hexdigest()}
            report['sdk_probe_sha256']=hashlib.sha256(args.sdk_probe_source.read_bytes()).hexdigest()
            deps=pathlib.Path(temporary)/'sdk-dependencies';deps.mkdir()
            with zipfile.ZipFile(f.JARS['domain']) as archive:
                for name in archive.namelist():
                    if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):(deps/pathlib.Path(name).name).write_bytes(archive.read(name))
            for artifact,path in [('core-banking-payments-sdk',args.payments_sdk),('core-banking-ledger-sdk',args.ledger_sdk)]:
                entries=list(deps.glob(artifact+'-*.jar'));expected=hashlib.sha256(path.read_bytes()).hexdigest()
                f.check(len(entries)==1 and hashlib.sha256(entries[0].read_bytes()).hexdigest()==expected,'actual domain embeds exact accepted '+artifact)
            classpath=os.pathsep.join([str(sdk),str(deps/'*')])
            with (f.ROOT/'domain-sdk-compile.log').open('w') as out:subprocess.run([str(pathlib.Path(args.java).with_name('javac')),'-cp',classpath,'-d',str(deps),str(args.sdk_probe_source)],stdout=out,stderr=subprocess.STDOUT,check=True)
            f.KEY=pathlib.Path(temporary)/'signing.pem';f.run(['openssl','genrsa','-out',str(f.KEY),'2048']);os.chmod(f.KEY,0o600)
            modulus=f.run(['openssl','rsa','-in',str(f.KEY),'-noout','-modulus']).decode().strip().split('=',1)[1]
            f.PUBLIC_KEY={'kty':'RSA','alg':'RS256','use':'sig','kid':'owned-peer','n':f.b64(bytes.fromhex(modulus)),'e':'AQAB'}
            fixture=http.server.ThreadingHTTPServer(('127.0.0.1',0),WorkerFixture);f.FIXTURE_URL='http://127.0.0.1:'+str(fixture.server_port);threading.Thread(target=fixture.serve_forever,daemon=True).start()
            for service in ['accounts','ledger','payments']:
                f.PEER_PORTS[service]=f.free_port();name='owned-pay-'+service+'-'+uuid.uuid4().hex[:8]
                container=f.run(f.DOCKER+['run','--rm','-d','--name',name,'-p','127.0.0.1::5432','-e','POSTGRES_PASSWORD='+f.BOOTSTRAP_PASSWORD,'postgres:17-alpine']).decode().strip()
                if not re.fullmatch('[a-f0-9]{64}',container):raise RuntimeError('Unexpected owned container ID')
                f.CONTAINERS[service]=container;f.DB_PORTS[service]=int(f.run(f.DOCKER+['port',container,'5432/tcp']).decode().strip().rsplit(':',1)[1])
                for attempt in range(100):
                    try:f.psql(container,'SELECT 1;');break
                    except subprocess.CalledProcessError:time.sleep(.1)
                f.psql(container,'CREATE DATABASE fnd_'+service+';')
                f.psql(container,"CREATE ROLE funds_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+f.RUNTIME_PASSWORD+"'; CREATE ROLE firefly_funds_policy_reader NOLOGIN NOSUPERUSER NOBYPASSRLS; CREATE ROLE firefly_funds_policy_writer NOLOGIN NOSUPERUSER NOBYPASSRLS; REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO funds_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO funds_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO funds_runtime;",'fnd_'+service)
            f.boot('accounts');f.boot('ledger');boot_payments();boot_domain();boot_experience()
            roles=f.psql(f.CONTAINERS['payments'],"SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname='funds_runtime'; SELECT bool_and(relrowsecurity AND relforcerowsecurity AND pg_get_userbyid(relowner)<>'funds_runtime') FROM pg_class WHERE relname IN('managed_payment_operation','managed_payment_action','managed_payment_effect','managed_payment_projection','managed_payment_history','managed_payment_outbox');",'fnd_payments').splitlines()
            f.check(roles==['t','t'],'actual payment runtime is nonprivileged/nonowner and all six managed tables FORCE RLS',roles)
            ids=[]
            for kind in ['BANK_CONTROL','CUSTOMER','CUSTOMER']:
                contract=str(uuid.uuid4());_,account=f.request('accounts','POST','/api/v1/accounts',{'contractId':contract,'accountNumber':'SYNTHETIC-'+str(uuid.uuid4()),'accountType':'CHECKING','currency':'USD','openDate':time.strftime('%Y-%m-%d'),'accountStatus':'OPEN','branchId':str(uuid.uuid4())},expected=201)
                account=account['accountId'];enrollment=str(uuid.uuid4());f.APPROVALS[account]={'tenantId':'owned','accountId':account,'contractId':contract,'currency':'USD','kind':kind};ids.append(account)
                f.request('ledger','POST',f.apath(account)+'/enrollment',{'enrollmentId':enrollment,'contractId':contract,'currency':'USD','kind':kind},[account],['funds:enroll','funds:tenant-bind','funds:bank-enroll'],201)
                f.request('accounts','POST',f.apath(account)+'/enrollment',{'enrollmentId':enrollment,'kind':kind},[account],['funds:enroll','funds:tenant-bind','funds:bank-enroll'],201)
                f.request('accounts','POST',f.apath(account)+'/enrollment/link',{'enrollmentId':enrollment},[account],['funds:link'],200)
                f.request('ledger','POST',f.apath(account)+'/controls',{'actionId':str(uuid.uuid4()),'state':'ACTIVE'},[account],['funds:control'],200)
            bank,source,destination=ids;pair=[source,destination]
            f.request('ledger','POST','/api/v1/tenants/owned/funds/funding',{'operationId':str(uuid.uuid4()),'bankControlAccountId':bank,'customerAccountId':source,'amount':100,'currency':'USD'},[bank,source],['funds:fund'],201)
            def create(amount=10,ttl=300):
                body={'actionId':str(uuid.uuid4()),'sourceAccountId':source,'destinationAccountId':destination,'amount':amount,'currency':'USD','ttlSeconds':ttl,'customerNotAfterEpochMillis':None}
                _,v=domain('POST',body=body,accounts=pair,expected=201);return body,v
            body,v=create()
            f.check(v['state']=='RESERVED' and v['pendingEffectId'] is None and v['reservationId'] is not None,'native domain Saga + two genuine SDK boundaries establishes real HELD receipt')
            state=f.funds(source);f.check(state['booked']==100 and state['held']==10,'real reserve holds exactly10 without booking a payment',state)
            _,replay=domain('POST',body=body,accounts=pair,expected=200);f.check(replay==v,'identical domain replay retains exact core version/financial identities')
            domain('POST',body=dict(body,ttlSeconds=301),accounts=pair,expected=409)
            domain('POST',body=body,accounts=[source],expected=403)
            domain('POST',body=body,accounts=pair,scopes=[s for s in SCOPES if s!='funds:reconcile'],expected=403)
            domain('GET','/'+v['paymentId'],accounts=[source],expected=404)
            decision={'actionId':str(uuid.uuid4()),'kind':'CONFIRM','expectedVersion':v['version']}
            _,v=domain('POST','/'+v['paymentId']+'/decisions',decision,pair,200)
            f.check(v['state']=='COMPLETED' and v['postingId'] and v['pendingEffectId'] is None,'real domain confirmation produces independently reconciled committed posting')
            f.check(f.funds(source)['booked']==90 and f.funds(source)['held']==0 and f.funds(destination)['booked']==10,'actual financial positions after domain COMMIT are exact')
            f.stop('experience');f.stop('domain');f.stop('payments');f.stop('accounts');f.stop('ledger');f.boot('accounts');f.boot('ledger');boot_payments();boot_domain();boot_experience()
            _,retained=domain('GET','/'+v['paymentId'],accounts=pair,expected=200);f.check(retained==v,'all five executable restarts retain actual complete financial receipt')
            f.request('ledger','POST','/api/v1/tenants/owned/funds/reservations/'+v['reservationId']+'/actions',{'actionId':str(uuid.uuid4()),'action':'REVERSE'},pair,['funds:reverse'],200)
            _,v=domain('POST','/'+v['paymentId']+'/recovery',{'actionId':str(uuid.uuid4()),'expectedVersion':v['version']},pair,200)
            f.check(v['state']=='REVERSED' and v['reversalId'] and v['reversalId']!=v['postingId'],'domain observes actual genuine reversal without collapsing it to cancellation')
            _,timeline=domain('GET','/'+v['paymentId']+'/history',accounts=pair,expected=200)
            completed=[e for e in timeline['items'] if e['type']=='LEDGER_COMMITTED'];reversed_events=[e for e in timeline['items'] if e['type']=='LEDGER_REVERSED']
            f.check(len(completed)==1 and len(reversed_events)==1 and completed[0]['postingId']==v['postingId'] and reversed_events[0]['postingId']==v['postingId'] and reversed_events[0]['reversalId']==v['reversalId'] and completed[0]['version']<reversed_events[0]['version'],'authorized domain timeline retains original completion and distinct reversal after restart')
            domain('GET','/'+v['paymentId']+'/history',accounts=[source],expected=404)
            domain('GET','/'+v['paymentId']+'/history',accounts=pair,scopes=[s for s in SCOPES if s!='payments:read'],expected=403)
            domain('GET','/'+v['paymentId']+'/history?limit=101',accounts=pair,expected=400)
            domain('GET','/'+v['paymentId']+'/history?afterVersion=-2',accounts=pair,expected=400)
            domain('GET','/'+v['paymentId']+'/history?afterVersion=0.5',accounts=pair,expected=400)
            _,first_page=domain('GET','/'+v['paymentId']+'/history?limit=2',accounts=pair,expected=200)
            f.check(len(first_page['items'])==2 and first_page['items'][0]['version']<first_page['items'][1]['version'],'domain history is bounded and ordered')
            after=first_page['items'][-1]['version'];_,next_page=domain('GET','/'+v['paymentId']+'/history?afterVersion='+str(after)+'&limit=2',accounts=pair,expected=200)
            f.check(all(e['version']>after for e in next_page['items']),'domain history cursor preserves strict version progression')

            f.check(f.funds(source)['booked']==100 and f.funds(destination)['booked']==0,'real reversal restores exact customer positions')
            _,v=create(7);_,v=domain('POST','/'+v['paymentId']+'/decisions',{'actionId':str(uuid.uuid4()),'kind':'CANCEL','expectedVersion':v['version']},pair,200)
            f.check(v['state']=='CANCELLED' and f.funds(source)['held']==0,'authorized domain cancellation releases actual hold and no booked effect')
            _,v=create(3,1);time.sleep(1.2)
            _,v=domain('POST','/'+v['paymentId']+'/recovery',{'actionId':str(uuid.uuid4()),'expectedVersion':v['version']},pair,200)
            f.check(v['state']=='EXPIRED' and f.funds(source)['held']==0,'authenticated OBSERVE invokes real ledger-clock EXPIRE and rechecks exact intent')
            for path in ['/api/orchestration/executions','/api/orchestration/workflows/instances']:
                token=f.token(['domain-managed'],pair,SCOPES);req=urllib.request.Request('http://127.0.0.1:'+str(f.PEER_PORTS['domain'])+path,headers={'Authorization':'Bearer '+token})
                try:urllib.request.urlopen(req,timeout=5);status=200
                except urllib.error.HTTPError as error:status=error.code
                f.check(status==403,'actual configured domain diagnostics deny external bearer '+path,status)
            probe_env=dict(os.environ,OWNED_EXPERIENCE_URL='http://127.0.0.1:'+str(f.PEER_PORTS['experience']),OWNED_LEDGER_URL='http://127.0.0.1:'+str(f.PEER_PORTS['ledger']),
                OWNED_EXPERIENCE_TOKEN=f.token(['experience-managed','domain-managed','payments-managed','ledger-funds'],pair,SCOPES+['funds:reverse']),
                OWNED_REVOKED_TOKEN=f.token(['experience-managed','domain-managed','payments-managed','ledger-funds'],[source],SCOPES),OWNED_SOURCE_ACCOUNT=source,OWNED_DESTINATION_ACCOUNT=destination)
            probe_log=f.ROOT/'domain-sdk-consumer.log'
            with probe_log.open('w') as out:subprocess.run([args.java,'-cp',str(deps)+os.pathsep+classpath,'ManagedPaymentExperienceSdkProbe'],env=probe_env,stdout=out,stderr=subprocess.STDOUT,check=True)
            probe_checks=[line.split('=',1)[1] for line in probe_log.read_text().splitlines() if line.startswith('OWNED_EXPERIENCE_SDK_CHECK=')]
            f.check(len(probe_checks)==15,'genuine experience SDK completed fifteen actual five-executable consumer assertions',len(probe_checks))
            for description in probe_checks:f.check(True,'genuine experience SDK: '+description)
            report['sdk_assertions']=len(probe_checks)
            f.check(f.funds(source)['booked']==100 and f.funds(source)['held']==0 and f.funds(destination)['booked']==0,'genuine experience SDK cancellation and linked reversal preserve exact final positions')
            # No customer decision scopes are granted to the native service actor.
            worker_accounts[:]=pair
            def pending(amount=1,ttl=300):
                request={'actionId':str(uuid.uuid4()),'sourceAccountId':source,'destinationAccountId':destination,'amount':amount,'currency':'USD','ttlSeconds':ttl,'customerNotAfterEpochMillis':None}
                _,created=core('POST',body=request,expected=201);return request,created
            _,late=pending(1,1)
            time.sleep(1.5)
            _,closed=domain('POST','/'+late['paymentId']+'/recovery',{'actionId':str(uuid.uuid4()),'expectedVersion':late['version']},pair,200)
            f.check(closed['state']=='EXPIRED'and closed['absenceReceiptId']and closed['reservationId']is None,'late retained RESERVE reaches actual ledger ABSENT_CLOSED and typed terminal no-effect receipt')
            gateway=LedgerFaultGateway('http://127.0.0.1:'+str(f.PEER_PORTS['ledger']))
            f.stop('domain');boot_domain(worker=True)
            request,created=pending();held=eventually(created['paymentId'],'RESERVED')
            f.check(f.funds(source)['held']==1 and held['initiator']=='synthetic-human','actual native worker discovers durable core work and reserves exactly once without impersonating customer')
            f.check(any(t['client']=='worker-one'for t in worker_tokens)and len(worker_tokens)>=2,'actual packaged Boot native OAuth manager performs repeated signed client-credentials acquisition')
            time.sleep(1.3);_,stable=core('GET','/'+created['paymentId'],expected=200)
            f.check(stable['state']=='RESERVED'and stable['version']==held['version'],'worker never automatically confirms or cancels a reserved customer instruction')
            _,history=domain('GET','/'+created['paymentId']+'/history',accounts=pair,expected=200)
            f.check(any(e['actor']=='synthetic-service-worker-one'for e in history['items']),'native worker actor is retained in business history separately from original customer')
            _,cancelled=domain('POST','/'+created['paymentId']+'/decisions',{'actionId':str(uuid.uuid4()),'kind':'CANCEL','expectedVersion':stable['version']},pair,200)
            f.check(cancelled['state']=='CANCELLED'and f.funds(source)['held']==0,'explicit customer cancellation alone releases the worker-established hold')
            _,expiring=pending(2,5);reserved=eventually(expiring['paymentId'],'RESERVED');expired=eventually(expiring['paymentId'],'EXPIRED',15)
            f.check(expired['ledgerState']=='EXPIRED'and f.funds(source)['held']==0,'due queued RESERVED work invokes authorized actual ledger-clock expiry')
            boot_domain(worker=True,replica='domain2',client='worker-two')
            _,raced=pending(3);raced=eventually(raced['paymentId'],'RESERVED')
            rows=f.psql(f.CONTAINERS['ledger'],"SELECT count(*) FROM ledger_funds_reservation WHERE operation_id='"+raced['paymentId']+"';",'fnd_ledger')
            f.check(rows=='1'and f.funds(source)['held']==3,'two native workers converge on one actual reservation and financial hold')
            _,raced=domain('POST','/'+raced['paymentId']+'/decisions',{'actionId':str(uuid.uuid4()),'kind':'CANCEL','expectedVersion':raced['version']},pair,200)
            f.check(raced['state']=='CANCELLED'and f.funds(source)['booked']==100 and f.funds(source)['held']==0,'two-worker round preserves exact final customer positions')
            # An actually changed issuer grant between discovery and command must deny admission.
            f.stop('domain2');f.stop('domain')
            worker_authority['alternate_denial']=True
            boot_domain(worker=True)
            _,revoked=pending(1)
            end=time.monotonic()+5
            while time.monotonic()<end:time.sleep(.1)
            _,untouched=core('GET','/'+revoked['paymentId'],expected=200)
            f.check(untouched['version']==0 and effect(revoked['paymentId'])[0]['attempts']==0 and f.funds(source)['held']==0,'actual fresh issuer grant withdrawal between queue discovery and command prevents any claim or money effect')
            worker_authority['alternate_denial']=False
            recovered=eventually(revoked['paymentId'],'RESERVED')
            _,recovered=domain('POST','/'+recovered['paymentId']+'/decisions',{'actionId':str(uuid.uuid4()),'kind':'CANCEL','expectedVersion':recovered['version']},pair,200)
            # Kill the actual service after remote financial commit but before the SDK reply.
            for action,amount in [('RESERVE',4),('COMMIT',5),('RELEASE',6)]:
                gateway.arm('after',action)
                if action=='RESERVE':
                    _,interrupted=pending(amount)
                    future=None;pool=None
                else:
                    _,interrupted=pending(amount);interrupted=eventually(interrupted['paymentId'],'RESERVED')
                    pool=concurrent.futures.ThreadPoolExecutor(max_workers=1)
                    decision={'actionId':str(uuid.uuid4()),'kind':'CONFIRM'if action=='COMMIT'else'CANCEL','expectedVersion':interrupted['version']}
                    future=pool.submit(domain,'POST','/'+interrupted['paymentId']+'/decisions',decision,pair)
                f.check(gateway.hit.wait(25),'actual ledger '+action+' response reached owned post-I/O barrier')
                f.check(gateway.hit_request['status']in[200,201],'actual ledger '+action+' successfully committed before process kill')
                position=f.funds(source)
                f.check(position['held']==amount if action=='RESERVE'else position['booked']==100-amount if action=='COMMIT'else position['held']==0,'actual ledger '+action+' financial position is visible before worker interruption')
                old=next(e for e in effect(interrupted['paymentId'])if e['kind']==action)
                _,inflight=core('GET','/'+interrupted['paymentId'],expected=200)
                f.check(old['state']=='CLAIMED'and old['lease']>old['now'],'native worker interruption preserves live owning-core claim and lease')
                crash_worker();gateway.resume.set()
                if future:
                    try:future.result(timeout=30)
                    except (OSError,urllib.error.URLError):pass
                    pool.shutdown(wait=True)
                boot_domain(worker=True)
                if action=='RESERVE':
                    final=eventually(interrupted['paymentId'],'RESERVED',35)
                    f.check(f.funds(source)['held']==amount,'replacement native worker recovers lost RESERVE reply without another hold')
                elif action=='COMMIT':
                    final=eventually(interrupted['paymentId'],'COMPLETED',35)
                    f.check(f.funds(source)['booked']==100-amount and f.funds(destination)['booked']==amount and f.funds(source)['held']==0,'replacement native worker recovers lost COMMIT reply without a second posting')
                else:
                    final=eventually(interrupted['paymentId'],'CANCELLED',35)
                    f.check(f.funds(source)['booked']==100 and f.funds(source)['held']==0,'replacement native worker recovers lost RELEASE reply without a booked payment')
                current=next(e for e in effect(interrupted['paymentId'])if e['kind']==action)
                f.check(current['id']==old['id']and current['participant']==old['participant']and current['fence']>old['fence']and current['attempts']>=2,'natural lease expiry retains exact effect/participant while fencing replacement claim')
                f.check(stale_ack(interrupted['paymentId'],old,inflight['version'])==409,'actual stale retained acknowledgment cannot overwrite replacement financial receipt')
                _,unchanged=core('GET','/'+interrupted['paymentId'],expected=200)
                f.check(unchanged==final,'stale acknowledgment leaves exact current customer receipt unchanged')
                if action=='RESERVE':
                    domain('POST','/'+final['paymentId']+'/decisions',{'actionId':str(uuid.uuid4()),'kind':'CANCEL','expectedVersion':final['version']},pair,200)
                elif action=='COMMIT':
                    f.request('ledger','POST','/api/v1/tenants/owned/funds/reservations/'+final['reservationId']+'/actions',{'actionId':str(uuid.uuid4()),'action':'REVERSE'},pair,['funds:reverse'],200)
                    _,final=domain('POST','/'+final['paymentId']+'/recovery',{'actionId':str(uuid.uuid4()),'expectedVersion':final['version']},pair,200)
                    f.check(final['state']=='REVERSED','explicit authorized reversal after recovered commit remains a distinct actual financial event')
            # A queued old reserve cannot establish a new hold after the ledger has closed its intent.
            gateway.arm('before','RESERVE');_,late_worker=pending(2,3)
            f.check(gateway.hit.wait(20),'old worker reserve admitted to private network barrier before ledger execution')
            crash_worker();boot_domain(worker=True)
            closed=eventually(late_worker['paymentId'],'EXPIRED',35)
            f.check(closed['absenceReceiptId']and closed['reservationId']is None and f.funds(source)['held']==0,'replacement worker obtains actual ABSENT_CLOSED after pinned deadline without guessing from local time or404')
            gateway.resume.set();end=time.monotonic()+5
            while time.monotonic()<end:
                old_requests=[e for e in gateway.requests if e['operation_id']==late_worker['paymentId']and e['action']=='RESERVE']
                if old_requests and all(e['status']is not None for e in old_requests):break
                time.sleep(.05)
            f.check(all(e['status']in[401,409]for e in old_requests),'delayed old reserve cannot create financial effect after authoritative closure')
            # Only the owner's due queue may schedule automatic retries; no SQL clock/data mutation.
            gateway.unavailable=True;_,retry=pending(1,300)
            observations=[]
            for attempt in range(1,11):
                row=wait_effect(retry['paymentId'],lambda rows:any(e['attempts']==attempt and e['state']in['PENDING','EXHAUSTED']for e in rows),40)[0]
                delay=min(30,2**min(5,attempt-1))
                if observations:f.check(row['claimed']>=observations[-1]['next'],'actual PostgreSQL claim timestamp respects the complete previous retained backoff before attempt '+str(attempt))
                observations.append(row)
                f.check(abs((row['next']-row['event'])-delay)<.5,'actual retained attempt '+str(attempt)+' preserves owning-core '+str(delay)+'second backoff')
                if attempt<10:
                    before=effect(retry['paymentId'])[0]
                    if before['now']<before['next']-.15:
                        time.sleep(.1);during=effect(retry['paymentId'])[0]
                        if during['now']<before['next']:
                            f.check(during['attempts']==attempt,'automatic ticks cannot bypass retained not-due attempt '+str(attempt))
                if attempt==6:
                    f.stop('domain');boot_domain(worker=True)
                    after=effect(retry['paymentId'])[0]
                    f.check(after['attempts']==6 and after['next']==row['next']and after['now']<after['next'],'actual worker restart preserves the complete retained30second backoff')
            f.check(observations[-1]['state']=='EXHAUSTED','exactly ten automatic financial-participant attempts retain exhausted state')
            gateway.unavailable=False
            f.stop('domain');boot_domain(worker=True);boot_domain(worker=True,replica='domain2',client='worker-two')
            time.sleep(3);exhausted=effect(retry['paymentId'])[0]
            _,queue=core('GET','/recovery?limit=25',expected=200)
            f.check(exhausted['attempts']==10 and exhausted['state']=='EXHAUSTED'and all(i['paymentId']!=retry['paymentId']for i in queue['items']),'two replacement workers never reopen exhausted budget or bypass owner queue')
            f.check(f.funds(source)['booked']==100 and f.funds(source)['held']==0 and f.funds(destination)['booked']==0,'all automatic crash/backoff/no-effect probes preserve exact final financial positions')
            report['backoff_observations']=observations
            report['ledger_gateway_requests']=gateway.requests
            report['worker_token_requests']=worker_tokens
            for service,entry in report['artifacts'].items():f.check(hashlib.sha256(pathlib.Path(entry['path']).read_bytes()).hexdigest()==entry['sha256'],'input artifact unchanged after actual qualification '+service)
            for name,entry in report['qualifier_inputs'].items():f.check(hashlib.sha256(pathlib.Path(entry['path']).read_bytes()).hexdigest()==entry['sha256'],'executed qualifier input unchanged after actual qualification '+name)
            report['status']='PASS'
    except BaseException as error:
        report['error']=type(error).__name__+(': '+str(error)[:1000] if not isinstance(error,subprocess.CalledProcessError) else ': owned subprocess failed');raise
    finally:
        for service in sorted(f.PROCESSES,key=lambda name:0 if name.startswith('domain')else 1):f.stop(service)
        if gateway:gateway.close()
        if fixture:fixture.shutdown();fixture.server_close()
        for container in f.CONTAINERS.values():subprocess.run(f.DOCKER+['stop','--time','5',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if temporary_directory:temporary_directory.cleanup()
        report.update(assertions=len(f.ASSERTIONS),checks=f.ASSERTIONS,logs=f.LOG_FILES,worker_token_requests=worker_tokens,owned_processes_remaining=len(f.PROCESSES))
        (f.ROOT/'reports/managed-payment-worker-result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'status':report['status'],'assertions':len(f.ASSERTIONS)}),flush=True)
if __name__=='__main__':main()
