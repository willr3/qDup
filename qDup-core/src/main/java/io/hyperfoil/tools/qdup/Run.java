package io.hyperfoil.tools.qdup;

import io.hyperfoil.tools.qdup.cmd.*;
import io.hyperfoil.tools.qdup.cmd.impl.Download;
import io.hyperfoil.tools.qdup.cmd.impl.RoleEnv;
import io.hyperfoil.tools.qdup.cmd.impl.ScriptCmd;
import io.hyperfoil.tools.qdup.config.Role;
import io.hyperfoil.tools.qdup.config.RunConfig;
import io.hyperfoil.tools.qdup.config.RunConfigBuilder;
import io.hyperfoil.tools.qdup.shell.AbstractShell;
import io.hyperfoil.tools.qdup.shell.ContainerShell;
import io.hyperfoil.tools.yaup.AsciiArt;
import io.hyperfoil.tools.yaup.HashedSets;
import io.hyperfoil.tools.yaup.StringUtil;
import io.hyperfoil.tools.yaup.json.Json;
import io.hyperfoil.tools.yaup.time.SystemTimer;
import org.jboss.logging.Logger;
import org.jboss.logmanager.formatters.ColorPatternFormatter;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.jboss.logmanager.handlers.ConsoleHandler;
import org.jboss.logmanager.handlers.FileHandler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.FileSystems;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;

import static io.hyperfoil.tools.qdup.cmd.PatternValuesMap.QDUP_GLOBAL;
import static io.hyperfoil.tools.qdup.cmd.PatternValuesMap.QDUP_GLOBAL_ABORTED;

/**
 * Created by wreicher
 *
 */
public class Run implements Runnable, DispatchObserver {

    private static final Logger logger = Logger.getLogger(MethodHandles.lookup().lookupClass());

    static class JitterCheck implements Runnable{
        @Override
        public void run() {
            long period = 50;
            long threshold = 100;
            long lastTimestamp = System.nanoTime();
            while (true) {
                try {
                    Thread.sleep(period);
                } catch (InterruptedException e) {
                    logger.debug("Interrupted, terminating jitter watchdog");
                    Thread.currentThread().interrupt();
                    return;
                }
                long currentTimestamp = System.nanoTime();
                long delay = TimeUnit.NANOSECONDS.toMillis(currentTimestamp - lastTimestamp);
                if (delay > threshold) {
                    logger.errorf("Jitter watchdog was not invoked for %d ms (threshold is %d ms); please check your GC settings.", delay, threshold);
                }
                lastTimestamp = currentTimestamp;
            }
        }
    }

    private volatile Integer stageIndex = -1;

    private final List<RunObserver> runObservers;

    private Map<String,Long> timestamps;
    private RunConfig config;
    private String outputPath;
    private AtomicBoolean aborted;
    private Coordinator coordinator;
    private Dispatcher dispatcher;
    private Profiles profiles;
    private Local local;

    private HashedSets<Host, Download> pendingDownloads;
    private HashedSets<Host,String> pendingDeletes;

    private CountDownLatch runLatch = new CountDownLatch(1);

    FileHandler fileHandler;
    Logger runLogger;// = XLoggerFactory.getXLogger(RUN_LOGGER_NAME);
    Logger stateLogger;// = XLoggerFactory.getXLogger(STATE_LOGGER_NAME);

    org.jboss.logmanager.Logger internalRunLogger;
    org.jboss.logmanager.Logger internalStateLogger;

    private List<Stage> stages;

    public Run(String outputPath,RunConfig config,Dispatcher dispatcher){
        if(config==null || dispatcher==null){
            throw new NullPointerException("Run config and dispatcher cannot be null");
        }

        this.runObservers = new LinkedList<>();

        this.config = config;

        this.outputPath = outputPath;
        this.dispatcher = dispatcher;
        this.dispatcher.addDispatchObserver(this);
        this.aborted = new AtomicBoolean(false);

        this.timestamps = new LinkedHashMap<>();
        this.profiles = new Profiles();
        this.coordinator = new Coordinator(config.getGlobals());
        this.local = new Local(config);
        this.stages = config.getStages();

        coordinator.addObserver((signal_name)->{
            runLogger.infof(
                    "%sreached %s%s",
                    config.isColorTerminal()?AsciiArt.ANSI_CYAN:"",
                    signal_name,
                    config.isColorTerminal()?AsciiArt.ANSI_RESET:""
            );
        });
        this.pendingDownloads = new HashedSets<>();
        this.pendingDeletes = new HashedSets<>();
    }

    private boolean removeLogger(){
        if(fileHandler!=null){
            fileHandler.close();
            fileHandler = null;
            return true;
        }
        return false;
    }

    private String getLoggerName(){
        String loggerName = "qdup."+getOutputPath().replaceAll(FileSystems.getDefault().getSeparator(),"_");
        return loggerName;
    }

    private String getStateLoggerName(){
        return getLoggerName().concat(".state");
    }

    boolean ensureLogger(){
        if(fileHandler==null) {
            synchronized (this) {
                if (fileHandler == null) {
                    fileHandler = new org.jboss.logmanager.handlers.FileHandler();
                    File outputDir = new File(getOutputPath());
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }
                    try {
                        fileHandler.setFile(new File(outputDir, "run.log"));
                    } catch (FileNotFoundException e) {

                    }
                    fileHandler.setAppend(true);//changed from false to avoid overriding
                    fileHandler.setAutoFlush(true);//was false
                    PatternFormatter formatter = new PatternFormatter(config.getConsoleFormatPattern());
                    fileHandler.setFormatter(formatter);
                    internalRunLogger = org.jboss.logmanager.Logger.getLogger(getLoggerName());
                    internalRunLogger.setUseParentHandlers(false);//to disable double console
                    internalRunLogger.setLevel(Level.ALL);
                    //internalRunLogger.setParent(org.jboss.logmanager.Logger.getGlobal());//was commented out //disallowed
                    internalStateLogger = org.jboss.logmanager.Logger.getLogger(internalRunLogger.getName() + ".state");
                    //internalStateLogger.setParent(org.jboss.logmanager.Logger.getGlobal());//was commented out //disallowed

                    internalRunLogger.addHandler(fileHandler);
                    fileHandler.setEnabled(true);
                    runLogger = Logger.getLogger(internalRunLogger.getName());
                    stateLogger = Logger.getLogger(internalStateLogger.getName());


                    assert runLogger!=null;
                    assert stateLogger!=null;
                }
            }
        }
        return fileHandler != null;
    }

    public void ensureConsoleLogging(){
        ensureLogger();
        if(internalRunLogger!=null){

            PatternFormatter formatter = getConfig().isColorTerminal() ? new ColorPatternFormatter(config.getConsoleFormatPattern()) : new PatternFormatter(config.getConsoleFormatPattern());
            ConsoleHandler consoleHandler = new ConsoleHandler(formatter);
            consoleHandler.setLevel(Level.ALL);
            internalRunLogger.addHandler(consoleHandler);
        }
    }


    public void addRunObserver(RunObserver observer){this.runObservers.add(observer);}
    public void removeRunObserver(RunObserver observer){this.runObservers.remove(observer);}
    public boolean hasRunObserver(){return !this.runObservers.isEmpty();}


    public void error(String message){
        ensureLogger();
        runLogger.error(message);

    }
    public void log(String message){
        ensureLogger();
        runLogger.info(message);
    }

    @Override
    public void preStart(){
        ensureLogger();
        timestamps.put(getStage().getName()+".start",System.currentTimeMillis());
    }
    @Override
    public void postStop(){
        //ensureLogger();//this was overriding the previous file :(
        timestamps.put(getStage().getName()+".stop",System.currentTimeMillis());
        boolean started = nextStage();
        if(!started){

        }
        //this.setupEnvDiff.clear();//why are we clearing the setup env diff? don't we need it for cleanup too?
    }
    private boolean nextStage(){
        boolean startDispatcher = false;
        if(hasRunObserver() && getStage()!=null){
            for(RunObserver observer : runObservers){
                observer.postStage(getStage());
            }
        }
        if(stageIndex >= 0 && stageIndex < stages.size()) {
            Stage currentStage = stages.get(stageIndex);
            if (currentStage.isPostDownload()) {
                runPendingDownloads();
            }
        }
        stageIndex++;
            if(stageIndex >= stages.size()){
            runPendingDownloads(); //added incase fo abort
            postRun();
            return false;
        }
        Stage targetStage = stages.get(stageIndex);
        startDispatcher = queueStage(targetStage);
        if(startDispatcher){
            if(hasRunObserver() && getStage()!=null){
                for(RunObserver observer: runObservers){
                    observer.preStage(getStage());
                }
            }
            dispatcher.start();
        }
        return startDispatcher;
    }

    public boolean isPreCleanup(){
        return stageIndex <= lastNonSequentialStageIndex();
    }
    public Stage getStage(){
        if(stageIndex < 0 && !stages.isEmpty()){
            return stages.get(0);
        }else if (stageIndex >= 0 && stageIndex < stages.size()){
            return stages.get(stageIndex);
        }else if (!stages.isEmpty()){
            return stages.get(stages.size()-1);
        }else{
            return null;
        }
    }
    public Local getLocal(){return local;}
    public RunConfig getConfig(){return config;}
    public boolean isAborted(){return aborted.get();}
    public Logger getRunLogger(){
        ensureLogger();
        return runLogger;
    }
    public Logger getStateLogger(){
        ensureLogger();
        return stateLogger;
    }

    public void addPendingDelete(Host host,String path){
        pendingDeletes.put(host,path);
    }
    public void addPendingDownload(Host host,String path,String destination, Long maxSize){
        pendingDownloads.put(host,new Download(path,destination,maxSize));
    }
    public void runPendingDeletes(){
        if(!pendingDeletes.isEmpty()){
            for(Host host : pendingDeletes.keys()){
                AbstractShell shell = AbstractShell.getShell(
                    host.getShortHostName()+"-pendingDelete",
                    host,
                    "",
                    getDispatcher().getCallback(),
                    getConfig().getState().getSecretFilter(),
                    false);
                Set<String> deleteList = pendingDeletes.get(host);
                for(String delete : deleteList){
                    shell.execSync("rm "+delete);
                }
                shell.close(true);
            }
        }
    }
    public void runPendingDownloads(){
        //TODO synchronize so only one thread tries the downloads (run ending while being aborted?)
        if(!pendingDownloads.isEmpty()){
            logger.infof("%s downloading queued downloads",config.getName());
            timestamps.put("downloadStart",System.currentTimeMillis());
            for(Host host : pendingDownloads.keys()){
                Set<Download> downloadList = pendingDownloads.get(host);
                for(Download pendingDownload : downloadList){
                    String downloadPath = pendingDownload.getPath();
                    String downloadDestination = pendingDownload.getDestination();
                    if(downloadDestination == null || downloadPath == null){
                        logger.error("NULL in queue-download "+downloadPath+" -> "+downloadDestination);
                    }else {
                        pendingDownload.execute(null, () -> local, () -> host);
                    }
                }
            }
            timestamps.put("downloadStop",System.currentTimeMillis());
            pendingDownloads.clear();
        }else{
        }
    }
    public void done(){
        coordinator.clearWaiters();
        dispatcher.stop(false);
    }
    public Json pendingDownloadJson(){
        Json rtrn = new Json();
        for(Host host : pendingDownloads.keys()){
            Json hostJson = new Json();
            rtrn.set(host.toString(),hostJson);
            Set<Download> pendings = pendingDownloads.get(host);
            for(Download pending : pendings){
                Json pendingJson = new Json();
                pendingJson.set("path",pending.getPath());
                pendingJson.set("dest",pending.getDestination());
                hostJson.add(pendingJson);
            }
        }
        return rtrn;
    }
    public Json getProfiles(){return profiles.getJson();}
    public void writeRunJson(){
        try (FileOutputStream out = new FileOutputStream(this.outputPath+File.separator+"run.json")) {

            Json toWrite = new Json();
            toWrite.set("version","0.0.1");
            toWrite.set("state",this.getConfig().getState().toJson());

            Json hosts = new Json(true);
            this.getConfig().getAllHostsInRoles().forEach(h->{
                hosts.add(h.toJson(true));
            });
            toWrite.set("hosts",hosts);

            Json latches = new Json();
            this.getCoordinator().getLatchTimes().forEach((key,value)->{
                latches.set(key,value);
            });
            Json counters = new Json();
            this.getCoordinator().getCounters().forEach((key,value)->{
                counters.set(key,value);
            });
            Json timestamps = new Json();
            this.timestamps.forEach((key,value)->{
                timestamps.set(key,value);
            });

            toWrite.set("timestamps",timestamps);
            toWrite.set("latches",latches);
            toWrite.set("counters",counters);
            toWrite.set("profiles",getProfiles());

            String filtered = getConfig().getState().getSecretFilter().filter(toWrite.toString(2));

            out.write(filtered.getBytes());
            out.flush();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int lastNonSequentialStageIndex(){
        int rtrn = 0;
        for(int i=0; i<stages.size(); i++){
            if(!stages.get(i).isSequential()){
                rtrn = i;
            }
        }
        return rtrn;
    }

    /**
     *
     * @param skipCleanUp
     * @return true iff this was the call that aborted the current run
     */
    public boolean abort(Boolean skipCleanUp){
        if(aborted.compareAndSet(false,true)){
            getConfig().getState().set(QDUP_GLOBAL+"."+QDUP_GLOBAL_ABORTED,true);//add ABORTED state for any cleanup scripts
            coordinator.clearWaiters();
            if (!skipCleanUp && stageIndex <= lastNonSequentialStageIndex()) {
                stageIndex = lastNonSequentialStageIndex();
            } else {
                logger.warn("Skipping cleanup - Abort has been defined to not run any cleanup scripts");
                stageIndex = stages.size();

            }
            dispatcher.stop(false);//interrupts working threads and stops dispatching next commands
            return true;//we aborted
        }else{
            logger.info("abort called when already aborted");
            dispatcher.stop(false);
            dispatcher.stopSystemTimers();
        }
        return false;
    }

    @Override
    public String toString(){return config.getName()+" -> "+outputPath;}

    //TODO separate coordinators for each stage?
    private boolean initializeCoordinator(){
        config.getSignalCounts().forEach((name,count)->{
            coordinator.setSignal(name,count.intValue());
        });
        return true;
    }

    @Override
    public void run() {
        ensureLogger();
        //if we have not yet started the run
        if(stageIndex == -1){

            //TODO enable jitter check? what amount of jitter matters for qDup?
//            Thread jitterThread = new Thread(new JitterCheck(),"jitter-check");
//            jitterThread.setDaemon(true);
//            jitterThread.start();

            timestamps.put("start",System.currentTimeMillis());
            if(config.hasErrors()){
                logger.error("cannot start run due to config errors");
                config.getErrors().forEach(e->logger.error(e.toString()));
                config.getErrors().forEach(e->runLogger.error(e.toString()));
                timestamps.put("stop",System.currentTimeMillis());
                return;
            }
            boolean coordinatorInitialized = initializeCoordinator();
            if(!coordinatorInitialized){
                logger.error("cannot start run due to coordinator errors");
                timestamps.put("stop",System.currentTimeMillis());
                return;
            }
            String tree = config.getState().tree();

            String filteredTree = getConfig().getState().getSecretFilter().filter(tree);
            stateLogger.debugf("%s starting state:\n%s",config.getName(),filteredTree);
            boolean ok = nextStage();
            if(ok) {
                try {
                    runLatch.await();
                } catch (InterruptedException e) {
                    logger.warn("interrupted while waiting for run to complete");
                    Thread.currentThread().interrupt();
                    //e.printStackTrace();
                } finally{
                    timestamps.put("stop",System.currentTimeMillis());
                }

            }else{
                //TODO failed to start
            }
            //moved to here because abort would avoid the cleanup in postRun()
            //will need to move if runLatch becomes optional

            //logAppender.stop(5,TimeUnit.SECONDS);
            removeLogger();
            writeRunJson();
        }
    }
    public boolean joinLatch(){
        try {
            runLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }
    public boolean joinLatch(long timeout,TimeUnit unit){
        try{
            return runLatch.await(timeout,unit);
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            return false;
        }

    }
    private boolean connectAll(List<Callable<Boolean>> toCall,int timeout){
        boolean ok = false;
        try {
            ok = getDispatcher().invokeAll(toCall/*,timeout, TimeUnit.SECONDS*/).stream().map((f) -> {

                boolean rtrn = false;
                try {
                    if(f != null) { //can be null when failed to authenticate
                        rtrn = f.get();
                    }else{

                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                return rtrn;
            })
                    .reduce(Boolean::logicalAnd).orElse(false);

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return ok;
    }
    private Script createTempDirectory(){
        Script script = new Script("create-qdup-temp");
        script.then(
           Cmd.sh(this.config.getGlobals().getSetting(RunConfig.MAKE_TEMP_KEY,RunConfigBuilder.MAKE_TEMP_CMD).toString())
           .then(Cmd.setState(State.HOST_PREFIX+RunConfigBuilder.TEMP_DIR))
        );

        return script;
    }
    private Script removeTempDirectory(){
        Script script = new Script("remove-qdup-temp");
        script.then(
           Cmd.sh(
              this.config.getGlobals().getSetting(RunConfig.REMOVE_TEMP_KEY,RunConfigBuilder.REMOVE_TEMP_CMD) +
              " " +
              StringUtil.PATTERN_PREFIX+RunConfigBuilder.TEMP_DIR+StringUtil.PATTERN_SUFFIX)
        );

        return script;
    }

    private boolean queuePostCleanupScripts(){
        logger.debugf("%s.post-cleanup",this);
        List<Callable<Boolean>> connectSessions = new LinkedList<>();

        Script setup = removeTempDirectory();
        config.getAllHostsInRoles().forEach(host->{
            connectSessions.add(()->{
                String name = "post-cleanup@"+host.getShortHostName();
                if(config.getGlobals().getSettings().has(RunConfig.TRACE_NAME)){
                    name = name+"."+Cmd.populateStateVariables(config.getGlobals().getSettings().getString(RunConfig.TRACE_NAME),null,getConfig().getState(),getCoordinator(),Json.fromMap(getTimestamps()));
                }
                AbstractShell shell = AbstractShell.getShell(
                        name,
                        host,
                        "",
                        getDispatcher().getCallback(),
                        getConfig().getState().getSecretFilter(),
                        isTrace(name)
                );
                shell.setName(name);

//                SshSession session = new SshSession(
//                    name,
//                    host,
//                    config.getKnownHosts(),
//                    config.getIdentity(),
//                    config.getPassphrase(),
//                    config.getTimeout(),
//                    "",
//                    getDispatcher().getScheduler(),
//                    isTrace(name));
                if ( shell.isReady() ) {
                    //TODO configure session delay
                    //session.setDelay(SuffixStream.NO_DELAY);
                    ScriptContext scriptContext = new ScriptContext(
                        shell,
                        config.getState().getChild(host.getHostName(), State.HOST_PREFIX),
                        this,
                        profiles.get(name),
                        setup,
                            (Boolean)config.getGlobals().getSetting("check-exit-code",false)
                    );
                    getDispatcher().addScriptContext(scriptContext);
                    return shell.isOpen();
                }
                else {
                    shell.close();
                    return false;
                }
            });
        });

        boolean ok = true;
        if(!connectSessions.isEmpty()) {
            ok = connectAll(connectSessions, 60);
            if (!ok) {
                abort(false);
            }

        }else{

        }
        return ok;

    }

    private boolean queuePreSetupScripts(){
        logger.debugf("%s.pre-setup",this);
        List<Callable<Boolean>> connectSessions = new LinkedList<>();

        Script setup = createTempDirectory();
        config.getAllHostsInRoles().forEach(host->{
            connectSessions.add(()->{
                String name = "pre-setup@"+host.getShortHostName()+"."+Cmd.populateStateVariables(config.getGlobals().getSettings().getString(RunConfig.TRACE_NAME),null,getConfig().getState(),getCoordinator(),Json.fromMap(getTimestamps()));
                AbstractShell shell = AbstractShell.getShell(
                        name,
                        host,
                        "",
                        getDispatcher().getCallback(),
                        getConfig().getState().getSecretFilter(),
                        isTrace(name)
                );
                shell.setName(name);
                if ( shell.isReady() ) {
                    //TODO configure session delay
                    //session.setDelay(SuffixStream.NO_DELAY);
                    ScriptContext scriptContext = new ScriptContext(
                        shell,
                        config.getState().getChild(host.getHostName(), State.HOST_PREFIX),
                        this,
                        profiles.get(name),
                        setup,
                            (Boolean)config.getGlobals().getSetting("check-exit-code",false)
                    );
                    getDispatcher().addScriptContext(scriptContext);
                    return shell.isOpen();
                }
                else {
                    shell.close();
                    return false;
                }
            });
        });

        boolean ok = true;
        if(!connectSessions.isEmpty()) {
            ok = connectAll(connectSessions, 60);
            if (!ok) {
                abort(false);
            }

        }else{

        }
        return ok;

    }
    private boolean queueSessions(List<Callable<Boolean>> connectSessions){
        boolean ok = true;
        if(!connectSessions.isEmpty()){
            ok = connectAll(connectSessions, 60);
            if (!ok) {
                abort(false);
            }
        }
        return ok;
    }
    private boolean queueStage(Stage targetStage){
        logger.debugf("%s.%s",this,getStage().getName());
        List<Callable<Boolean>> connectSessions = new LinkedList<>();
        Role allRole = config.getRole(RunConfigBuilder.ALL_ROLE);
        config.getRoleNames().forEach(roleName->{
            final Role role = config.getRole(roleName);
            List<NamedCmd> toCall = new ArrayList<>();
            if(!role.getStage(targetStage).isEmpty()){
                if(targetStage.isSequential()){
                    final Script sequential = new Script(targetStage.name());
                    if(!targetStage.ignoreEnv()){
                        sequential.then(new RoleEnv(role,true));
                    }
                    role.getStage(targetStage).forEach(cmd->{
                        sequential.then(cmd);
                    });
                    if(!targetStage.ignoreEnv()){
                        sequential.then(new RoleEnv(role,false));
                    }
                    toCall.add(sequential);
                }else{
                    toCall.addAll(role.getStage(targetStage));
                }
                for(NamedCmd script : toCall){
                    for(Host host : role.getHosts(config)){
                        NamedCmd copy = (NamedCmd)script.deepCopy();
                        //TODO should we change back to host name instead of short name?
                        State hostState = config.getState().getChild(host.getShortHostName(), State.HOST_PREFIX);
                        State scriptState = hostState.getChild(copy.getName()).getChild("id="+copy.getUid());
                        String profileName = copy.getName()+"-"+copy.getUid()+"@"+host.getShortHostName();
                        SystemTimer timer = profiles.get(profileName);
                        profiles.getProperties(profileName).set("host",host.getShortHostName());
                        profiles.getProperties(profileName).set("role",role.getName());
                        profiles.getProperties(profileName).set("script",copy.getName());
                        profiles.getProperties(profileName).set("scriptId",copy.getUid());
                        Env env = role.hasEnvironment(host) ? role.getEnv(host) : new Env();
                        if(!role.getName().equals(RunConfigBuilder.ALL_ROLE) && allRole!=null && allRole.hasEnvironment(host)){
                            env.merge(allRole.getEnv(host));
                        }
                        String setupCommand = env.getDiff().getCommand();
                        connectSessions.add(()->{
                            String name = copy.getName()+":"+copy.getUid()+"@"+host.getShortHostName()+"."+Cmd.populateStateVariables(config.getGlobals().getSettings().getString(RunConfig.TRACE_NAME),null,getConfig().getState(),getCoordinator(),Json.fromMap(getTimestamps()));
                            timer.start("connect:" + host.toString());
                            AbstractShell shell = AbstractShell.getShell(
                                    name,
                                    host,
                                    setupCommand,
                                    getDispatcher().getCallback(),
                                    getConfig().getState().getSecretFilter(),
                                    isTrace(name)
                            );
                            shell.setName(name);
                            if (shell.isReady()) {
                                timer.start("context:" + host.toString());
                                ScriptContext scriptContext = new ScriptContext(
                                        shell,
                                        scriptState,
                                        this,
                                        timer,
                                        copy,
                                        (Boolean)config.getGlobals().getSetting("check-exit-code",false)
                                );
                                scriptContext.setRoleName(role.getName());
                                if(config.isStreamLogging()){
                                    shell.addLineObserver("stream",(line)->{
                                        ensureLogger();
                                        scriptContext.log(line);
                                    });
                                }
                                getDispatcher().addScriptContext(scriptContext);
                                boolean rtrn = shell.isOpen();
                                timer.start("waiting for start");
                                return rtrn;
                            } else {
                                logger.error(targetStage.getName()+" failed to connect "+host.getSafeString()
                                        +(host.hasContainerId() ? " "+host.getContainerId() : "")
                                        +(host.hasPassword() ?
                                        ", verify ssh works with the provided username and password" :
                                        ", verify password-less ssh works with the selected keys"
                                                +"\n"+shell.peekOutput())
                                );
                                shell.close();
                                return false;
                            }
                        });
                    }
                }
            }
        });
        boolean ok = true;
        if(!connectSessions.isEmpty()) {
            ok = connectAll(connectSessions, 60);
            if (!ok) {
                getRunLogger().error("failed to connect all ssh sessions for "+targetStage.name());
                abort(true);
            }
        }else{
            //there is nothing for this stage
        }
        return ok;
    }

    private boolean isTrace(String value){
        //return true; //temporarily debug everything
        return config.getTracePatterns().stream().anyMatch(pattern -> value.contains(pattern) || Pattern.matches(pattern,value));
    }
    private void postRun(){
        logger.debugf("%s.postRun",this);
        getConfig().getAllHostsInRoles().forEach(host->{
            if(host.isContainer() && host.needStopContainer() && host.startedContainer()){
                if(host.hasStopContainer()){
                    ContainerShell containerShell = new ContainerShell(
                        host.getShortHostName()+"-container-stop",
                        host, 
                        "",
                        dispatcher.getScheduler(), 
                        getConfig().getState().getSecretFilter(), 
                        false);
                    containerShell.stopContainerIfStarted();
                }
            }
        });
        String tree = config.getState().tree();//tree filters itself
        stateLogger.debugf("%s closing state:\n%s",config.getName(),tree);
        runLatch.countDown();
    }
    public Dispatcher getDispatcher(){return dispatcher;}
    public Coordinator getCoordinator(){return coordinator;}
    public String getOutputPath(){ return outputPath;}

    public Map<String,Long> getTimestamps(){return Collections.unmodifiableMap(new LinkedHashMap<>(timestamps));}
}
