#!/usr/bin/env python3
"""Private actual four-executable managed-payment domain qualification.

The accepted financial cores remain immutable inputs. The real domain uses native CQRS,
per-command Jafly SagaEngine and genuine generated payment/ledger SDKs. Only fresh private
PostgreSQL containers, synthetic identities and random loopback ports are used. This does
not establish experience, automatic-worker, bank-rail or production acceptance.
"""
import argparse
import concurrent.futures
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

SCOPES=['payments:create','payments:read','payments:coordinate','payments:confirm','payments:cancel','payments:recover',
        'funds:read','funds:reserve','funds:commit','funds:release','funds:reconcile','funds:expire']
BASE='/api/v1/tenants/owned/internal-payments'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for service in ['accounts','ledger','payments','domain']:
        parser.add_argument('--'+service+'-jar',type=pathlib.Path,required=True)
    parser.add_argument('--domain-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--payments-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--ledger-sdk',type=pathlib.Path,required=True)
    parser.add_argument('--sdk-probe-source',type=pathlib.Path,default=pathlib.Path(__file__).with_name('ManagedPaymentDomainSdkProbe.java'))
    parser.add_argument('--funds-fixture-script',type=pathlib.Path,required=True)
    parser.add_argument('--output-dir',type=pathlib.Path,required=True)
    parser.add_argument('--java',default=str(pathlib.Path(os.environ['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in os.environ else shutil.which('java'))
    parser.add_argument('--docker-host',default=os.environ.get('DOCKER_HOST'))
    args=parser.parse_args()
    if not args.java or not shutil.which('docker'):parser.error('JDK25 and Docker are required')
    for path in [args.accounts_jar,args.ledger_jar,args.payments_jar,args.domain_jar,args.domain_sdk,args.payments_sdk,args.ledger_sdk,args.sdk_probe_source,args.funds_fixture_script]:
        if not path.is_file():parser.error('Missing owned input: '+str(path))
    spec=importlib.util.spec_from_file_location('owned_funds_fixture',args.funds_fixture_script)
    f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
    f.ROOT=args.output_dir.resolve();f.ROOT.mkdir(parents=True,exist_ok=True);(f.ROOT/'reports').mkdir(exist_ok=True)
    f.JAVA=args.java;f.DOCKER=[shutil.which('docker')]+(['--host',args.docker_host] if args.docker_host else [])
    f.CONTAINERS={};f.DB_PORTS={};f.JARS={};f.KEY=None
    f.BOOTSTRAP_PASSWORD=secrets.token_urlsafe(24);f.RUNTIME_PASSWORD=secrets.token_urlsafe(24);f.CLIENT_SECRET=secrets.token_urlsafe(24)
    report={'status':'FAILED','scope':'actual four-executable domain/payment/accounts/ledger plus genuine domain SDK; experience/worker pending','artifacts':{},'qualifier_sha256':hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(args.funds_fixture_script.read_bytes()).hexdigest()}
    fixture=None
    def domain(method,path='',body=None,accounts=(),expected=None,scopes=None):
        token=f.token(['domain-managed','payments-managed','ledger-funds'],accounts,SCOPES if scopes is None else scopes)
        request=urllib.request.Request('http://127.0.0.1:'+str(f.PEER_PORTS['domain'])+BASE+path,data=None if body is None else json.dumps(body).encode(),method=method,headers={'Content-Type':'application/json','Authorization':'Bearer '+token,'Idempotency-Key':str(body.get('actionId',uuid.uuid4())) if body else str(uuid.uuid4())})
        try:
            with urllib.request.urlopen(request,timeout=30) as response:status,raw=response.status,response.read()
        except urllib.error.HTTPError as error:status,raw=error.code,error.read()
        value=json.loads(raw) if raw else None
        if expected is not None:f.check(status==expected,method+' actual domain '+path+' status='+str(expected),(status,value))
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
    def boot_domain():
        f.PEER_PORTS.setdefault('domain',f.free_port())
        log=(f.ROOT/('domain-boot-'+str(time.time_ns())+'.log')).open('w');f.LOG_FILES.append(str(log.name))
        env=dict(os.environ,SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(f.PEER_PORTS['domain']),FIREFLY_EDA_ENABLED='false',FIREFLY_STEPEVENTS_ENABLED='false',
            MANAGED_PAYMENTS_DOMAIN_ISSUER=f.ISSUER,MANAGED_PAYMENTS_DOMAIN_AUDIENCE='domain-managed',MANAGED_PAYMENTS_DOMAIN_JWKS_URI=f.FIXTURE_URL+'/jwks',
            MANAGED_PAYMENTS_DOMAIN_ALLOW_LOOPBACK_HTTP='true',MANAGED_PAYMENTS_DOMAIN_DELEGATION_MODE='validated-multi-audience',
            MANAGED_PAYMENTS_DOMAIN_CORE_URL='http://127.0.0.1:'+str(f.PEER_PORTS['payments']),MANAGED_PAYMENTS_DOMAIN_CORE_AUDIENCE='payments-managed',
            MANAGED_PAYMENTS_DOMAIN_LEDGER_URL='http://127.0.0.1:'+str(f.PEER_PORTS['ledger']),MANAGED_PAYMENTS_DOMAIN_LEDGER_AUDIENCE='ledger-funds',
            LOGGING_LEVEL_ROOT='WARN',LOGGING_LEVEL_IO_FIREFLYSOFTWARE='INFO',LOGGING_LEVEL_ORG_FIREFLYFRAMEWORK='WARN')
        process=subprocess.Popen([f.JAVA,'-jar',str(f.JARS['domain'])],env=env,stdout=log,stderr=subprocess.STDOUT);f.PROCESSES['domain']=(process,log)
        end=time.monotonic()+90
        while time.monotonic()<end:
            if process.poll() is not None:raise RuntimeError('domain exited; inspect '+str(log.name))
            try:urllib.request.urlopen('http://127.0.0.1:'+str(f.PEER_PORTS['domain'])+'/v3/api-docs',timeout=1).close();return
            except (OSError,urllib.error.HTTPError):time.sleep(.2)
        raise RuntimeError('owned domain startup timeout')
    try:
        with tempfile.TemporaryDirectory(prefix='owned-payment-domain-') as temporary:
            for service,path in [('accounts',args.accounts_jar),('ledger',args.ledger_jar),('payments',args.payments_jar),('domain',args.domain_jar)]:
                frozen=pathlib.Path(temporary)/(service+'.jar');shutil.copy2(path,frozen);sha=hashlib.sha256(frozen.read_bytes()).hexdigest()
                if sha!=hashlib.sha256(path.read_bytes()).hexdigest():raise RuntimeError('Input changed during copy: '+service)
                f.JARS[service]=frozen;report['artifacts'][service]={'path':str(path.resolve()),'sha256':sha}
            sdk=pathlib.Path(temporary)/'domain-sdk.jar';shutil.copy2(args.domain_sdk,sdk)
            report['artifacts']['domain_sdk']={'path':str(args.domain_sdk.resolve()),'sha256':hashlib.sha256(sdk.read_bytes()).hexdigest()}
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
            fixture=http.server.ThreadingHTTPServer(('127.0.0.1',0),f.Fixture);f.FIXTURE_URL='http://127.0.0.1:'+str(fixture.server_port);threading.Thread(target=fixture.serve_forever,daemon=True).start()
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
            f.boot('accounts');f.boot('ledger');boot_payments();boot_domain()
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
            f.stop('domain');f.stop('payments');boot_payments();boot_domain()
            _,retained=domain('GET','/'+v['paymentId'],accounts=pair,expected=200);f.check(retained==v,'domain and payment executable restart retain actual complete receipt')
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
            probe_env=dict(os.environ,OWNED_DOMAIN_URL='http://127.0.0.1:'+str(f.PEER_PORTS['domain']),OWNED_LEDGER_URL='http://127.0.0.1:'+str(f.PEER_PORTS['ledger']),
                OWNED_DOMAIN_TOKEN=f.token(['domain-managed','payments-managed','ledger-funds'],pair,SCOPES+['funds:reverse']),
                OWNED_REVOKED_TOKEN=f.token(['domain-managed','payments-managed','ledger-funds'],[source],SCOPES),OWNED_SOURCE_ACCOUNT=source,OWNED_DESTINATION_ACCOUNT=destination)
            probe_log=f.ROOT/'domain-sdk-consumer.log'
            with probe_log.open('w') as out:subprocess.run([args.java,'-cp',str(deps)+os.pathsep+classpath,'ManagedPaymentDomainSdkProbe'],env=probe_env,stdout=out,stderr=subprocess.STDOUT,check=True)
            probe_checks=[line.split('=',1)[1] for line in probe_log.read_text().splitlines() if line.startswith('OWNED_DOMAIN_SDK_CHECK=')]
            f.check(len(probe_checks)==15,'genuine domain SDK completed all fifteen actual-process consumer assertions',len(probe_checks))
            for description in probe_checks:f.check(True,'domain SDK: '+description)
            report['sdk_assertions']=len(probe_checks)
            f.check(f.funds(source)['booked']==100 and f.funds(source)['held']==0 and f.funds(destination)['booked']==0,'genuine domain SDK cancellation and linked reversal preserve exact final positions')
            for service,entry in report['artifacts'].items():f.check(hashlib.sha256(pathlib.Path(entry['path']).read_bytes()).hexdigest()==entry['sha256'],'input artifact unchanged after actual qualification '+service)
            report['status']='PASS'
    except BaseException as error:
        report['error']=type(error).__name__+(': '+str(error)[:1000] if not isinstance(error,subprocess.CalledProcessError) else ': owned subprocess failed');raise
    finally:
        for service in list(f.PROCESSES):f.stop(service)
        if fixture:fixture.shutdown();fixture.server_close()
        for container in f.CONTAINERS.values():subprocess.run(f.DOCKER+['stop','--time','5',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        report.update(assertions=len(f.ASSERTIONS),checks=f.ASSERTIONS,logs=f.LOG_FILES)
        (f.ROOT/'reports/managed-payment-domain-result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'status':report['status'],'assertions':len(f.ASSERTIONS)}),flush=True)
if __name__=='__main__':main()
