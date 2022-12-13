package io.hyperfoil.tools.qdup.cmd;

import io.hyperfoil.tools.qdup.Coordinator;
import io.hyperfoil.tools.qdup.Host;
import io.hyperfoil.tools.qdup.Local;
import io.hyperfoil.tools.qdup.Run;
import io.hyperfoil.tools.qdup.SshSession;
import io.hyperfoil.tools.qdup.State;
import io.hyperfoil.tools.qdup.cmd.impl.ScriptCmd;
import io.hyperfoil.tools.yaup.AsciiArt;
import io.hyperfoil.tools.yaup.PopulatePatternException;
import io.hyperfoil.tools.yaup.StringUtil;
import io.hyperfoil.tools.yaup.json.Json;
import io.hyperfoil.tools.yaup.time.SystemTimer;
import org.slf4j.Logger;
import org.slf4j.ext.XLogger;
import org.slf4j.ext.XLoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;

/**
 * Created by wreicher
 * The context for executing the command and provides the run api to the commands
 */
public class ScriptContext implements Context, Runnable{

    final static XLogger logger = XLoggerFactory.getXLogger(MethodHandles.lookup().lookupClass());

    private class SharedScriptContext extends ScriptContext{
        public SharedScriptContext(SystemTimer timer,Cmd root, boolean checkExitCode){
            super(ScriptContext.this.getSession(),
               ScriptContext.this.getState(),
               ScriptContext.this.getRun(),
               timer,
               root,checkExitCode);
        }
        @Override
        public void close(){
            ScriptContext.this.checkClose();
        }
    }

    /**
     * Tracks the future for a Cmd's timer and the associated parent cmd with the timer
     */
    private class TimerEntry {
        private ScheduledFuture<?> future;
        private Cmd target;
        private Cmd toRun;

        public TimerEntry(Cmd target, ScheduledFuture future, Cmd toRun){
            this.target = target;
            this.future = future;
            this.toRun = toRun;
        }

        public Cmd getToRun(){return toRun;}
        public Cmd getTarget(){return target;}
        public ScheduledFuture getFuture(){return future;}
        public void cancel(Cmd current){
            cancel(current,false);
        }

        /**
         * only cancel if forced or the target is not a loop ancestor of the current command
         * @param current
         * @param force
         */
        public void cancel(Cmd current,boolean force){
            if(force || current == null || !(target instanceof LoopCmd && current.hasAncestor(target))){
                getFuture().cancel(false);
            }
        }
    }

    /**
     * calls next if the target command is still the active command, otherwise skip
     */
    private class ActiveCheckCmd extends Cmd{

        private Cmd target;

        public ActiveCheckCmd(Cmd target){
            this.target = target;
        }

        @Override
        public void run(String input, Context context) {
            //TODO do we check if the target is a parent loop?
            if(target.equals(currentCmd) || ( (target instanceof LoopCmd) && currentCmd.hasAncestor(target) )){
                context.next(input);
            }else{
                context.skip(input);
            }
        }

        @Override
        public Cmd copy() {
            return new ActiveCheckCmd(target);
        }
    }

    private static final AtomicReferenceFieldUpdater<ScriptContext,Cmd> currentCmdUpdater =
            AtomicReferenceFieldUpdater.newUpdater(ScriptContext.class,Cmd.class,"currentCmd");
    private static final String CLOSE_QUEUE = "CLOSE_LINE_QUEUE_"+System.currentTimeMillis();

    private final SshSession session;
    private final Cmd rootCmd;
    private boolean checkExitCode;
    private final State state;
    private final Run run;
    private final SystemTimer timer;
    private SystemTimer cmdTimer = null;
    private ContextObserver observer = null;
    private Semaphore lineQueueSemaphore;
    private BlockingQueue<String> lineQueue;

    private AtomicInteger sessionCounter = new AtomicInteger(1);

    private List<TimerEntry> timeouts;

    private volatile Cmd currentCmd;
    private final Map<String,Cmd> signalCmds = new HashMap<>();

    long startTime = -1;
    long updateTime = -1;

    private String cwd="";

    public String getCwd(){return cwd;}
    public void setCwd(String cwd){
        this.cwd = cwd;
    }

    public boolean checkExitCode(){return checkExitCode;}

    public String getContextId(){
        //TODO use a StringBuilder to correctly handle missing session or root
        Cmd root = getRootCmd();
        String cmdName = "";
        if(root != null){
            if( root instanceof ScriptCmd){
                cmdName = ((ScriptCmd)root).getName();
            }else if (root instanceof Script){
                cmdName = ((Script)root).getName();
            }else{
                cmdName = root.toString();
            }
        }
        return (cmdName.isEmpty() ? "" : cmdName+":"+getRootCmd().getUid()+"@") + (getSession()!=null ? getSession().getHost().toString() : "");
    }

    public ScriptContext(SshSession session, State state, Run run, SystemTimer timer, Cmd rootCmd, boolean checkExitCode){
        this(session,state,run,timer,rootCmd.deepCopy(),null,checkExitCode);
    }
    private ScriptContext(SshSession session, State state, Run run, SystemTimer timer, Cmd rootCmd,Cmd setCurrentCmd, boolean checkExitCode){
        this.session = session;
        this.rootCmd = rootCmd;
        this.currentCmd = null;
        setCurrentCmd(null,setCurrentCmd==null?rootCmd:setCurrentCmd);
        this.state = state;
        this.run = run;
        this.timer = timer;
        this.cmdTimer = timer;
        this.checkExitCode = checkExitCode;

        if(this.session!=null){
            session.addLineObserver(
                getClass().getSimpleName(),
                (line)->{
                    this.update(line);
                }
            );
        }
        this.lineQueueSemaphore = new Semaphore(1);
        this.lineQueue = new LinkedBlockingQueue<>();
        this.timeouts = new LinkedList<>();
    }
    private void clearTimers(Cmd currentCmd){
        timeouts.forEach(timeout->timeout.cancel(currentCmd));
    }

    public ScriptContext newChildContext(SystemTimer timer,Cmd root){
        sessionCounter.incrementAndGet();
        return new SharedScriptContext(timer,root,false);
    }

    public void setObserver(ContextObserver observer){
        this.observer = observer;
    }
    protected long getStartTime(){return startTime;}
    protected void setStartTime(long startTime){this.startTime = startTime;}
    protected long getUpdateTime(){return updateTime;}
    protected void setUpdateTime(long updateTime){this.updateTime = updateTime;}

    protected Cmd getRootCmd(){return rootCmd;}
    public Cmd getCurrentCmd(){return currentCmd;}

    public Run getRun(){return run;}

    public Logger getRunLogger(){
        return run.getRunLogger();
    }

    public SystemTimer getContextTimer(){return timer;}
    public SystemTimer getCommandTimer(){return cmdTimer;}
    public String getRunOutputPath(){
        return run.getOutputPath();
    }
    public Script getScript(String name,Cmd command){
        return run.getConfig().getScript(name,command,this.getState());
    }
    public SshSession getSession(){
        return session;
    }


    @Override
    public Json getTimestamps(){
        return Json.fromMap(run.getTimestamps());
    }

    @Override
    public Host getHost() {
        return session.getHost();
    }

    public Coordinator getCoordinator(){return run.getCoordinator();}

    @Override
    public void close() {
        checkClose();
    }

    private void checkClose(){
        int currentCount = this.sessionCounter.decrementAndGet();
        if(currentCount==0) {
            session.close();
        }
    }

    public State getState(){return state;}
    @Override
    public void addPendingDownload(String path,String destination, Long maxSize){
        run.addPendingDownload(session.getHost(),path,destination, maxSize);
    }
    @Override
    public void addPendingDelete(String path){
        run.addPendingDelete(session.getHost(),path);
    }
    public void abort(Boolean skipCleanup){
        run.abort(skipCleanup);
    }
    public void done(){
        run.done();
    }
    public Local getLocal(){return run.getLocal();}

    @Override
    public void schedule(Runnable runnable, long delayMs) {
        run.getDispatcher().getScheduler().schedule(runnable,delayMs,TimeUnit.MILLISECONDS);
    }

    protected ContextObserver getObserver(){return observer;}

    private void observerPreStart(Cmd command){
        if(observer!=null){
            observer.preStart(this,command);
        }
    }
    private void observerPreNext(Cmd command,String output){
        if(observer!=null){
            observer.preStop(this,command,output);
            observer.preNext(this,command,output);
        }
    }
    private void observerPreSkip(Cmd command,String output){
        if(observer!=null){
            observer.preStop(this,command,output);
            observer.preSkip(this,command,output);
        }
    }
    private void observerUpdate(Cmd command,String output){
        if(observer!=null){
            observer.onUpdate(this,command,output);
        }
    }
    private void observerDone(){
        if(observer!=null){
            observer.onDone(this);
        }
    }
    @Override
    public void terminal(String output){
        String filteredMessage = state.getSecretFilter().filter(output);
        run.getRunLogger().info(filteredMessage);
    }
    @Override
    public boolean isColorTerminal(){
        return run.getConfig().isColorTerminal();
    }

    public void log(String message){
        String rootString;
        if(rootCmd instanceof Script){
            rootString = ((Script)rootCmd).getName();
        }else if (rootCmd instanceof ScriptCmd){
            rootString = ((ScriptCmd)rootCmd).getName();
        }else{
            rootString = rootCmd.toString();
        }
        String filteredMessage = state.getSecretFilter().filter(message);
        getRunLogger().info("{}:{}@{}:{}",rootString,rootCmd.getUid(),getHost().getShortHostName(),filteredMessage);
    }
    public void error(String message){
        String rootString;
        if(rootCmd instanceof Script){
            rootString = ((Script)rootCmd).getName();
        }else if (rootCmd instanceof ScriptCmd){
            rootString = ((ScriptCmd)rootCmd).getName();
        }else{
            rootString = rootCmd.toString();
        }
        String filteredMessage = state.getSecretFilter().filter(message);
        if(isColorTerminal()){
            getRunLogger().error(AsciiArt.ANSI_RED+"{}:{}@{}:{}"+AsciiArt.ANSI_RESET,rootString,rootCmd.getUid(),getHost().getShortHostName(),filteredMessage);
        }else{
            getRunLogger().error("{}:{}@{}:{}",rootString,rootCmd.getUid(),getHost().getShortHostName(),filteredMessage);
        }

    }

    public void closeLineQueue(){
        //only close if something is listening
        //may need to send close before listener has the semaphore (e.g. tests)
        lineQueue.add(CLOSE_QUEUE);
    }


    private void advanceCommand(Cmd currentCmd, Cmd nextCmd, String output){
        clearTimers(nextCmd);
        if(!signalCmds.isEmpty()){
            signalCmds.forEach((name,onsignal)->{
                getCoordinator().removeWaiter(name,onsignal);
            });
            signalCmds.clear();
        }
        if(currentCmd!=null){
            if(currentCmd.hasWatchers()){
                closeLineQueue();
            }
            currentCmd.postRun(output,this);
            boolean changed = setCurrentCmd(currentCmd,nextCmd);
            if(changed) {
                startCurrentCmd();
            }else{
                //TODO how to handle failing to change?
                System.out.printf("%s%n",AsciiArt.ANSI_BLUE+"failed to change to "+nextCmd+AsciiArt.ANSI_RESET);
            }
        }
    }
    @Override
    public void next(String output) {
        getContextTimer().start("next");
        Cmd cmd = getCurrentCmd();
        cmd.setOutput(output);
        Cmd toCall = cmd.getNext();
        observerPreNext(cmd,output);
        advanceCommand(cmd,toCall,output);
    }

    @Override
    public void skip(String output) {
        getContextTimer().start("skip");
        Cmd cmd = getCurrentCmd();
        cmd.setOutput(output);
        Cmd toCall = cmd.getSkip();
        observerPreSkip(cmd,output);
        advanceCommand(cmd,toCall,output);
    }
    protected void startCurrentCmd(){
        Run run = getRun();
        if(run!=null) {
            getContextTimer().start("waiting in run queue");
            run.getDispatcher().submit(this);
        }
    }

    @Override
    public void update(String output) {
        long timestamp = System.currentTimeMillis();
        setUpdateTime(timestamp);
        //updateTime = timestamp;
        Cmd cmd = getCurrentCmd();
        if(cmd!=null){
            observerUpdate(cmd,output);
            lineQueue.add(output);
        }
    }
    public boolean setCurrentCmd(Cmd current,Cmd next){
        currentCmd = next;
        boolean changed = true;//currentCmdUpdater.compareAndSet(this,current,next);

        if(logger.isTraceEnabled()) {
            StringBuffer sb = new StringBuffer();
            sb.append("setCurrent\n  current:" + current + "\n  next:" + next + "\n  changed:" + changed);
            Arrays.asList(Thread.currentThread().getStackTrace()).forEach(ste -> {
                sb.append("\n    " + ste.toString());
            });
            logger.trace(sb.toString());
        }

        if(changed){

        }else{

        }
        return changed;
    }

    private void addTimer(Cmd toWatch,Cmd toRun,long timeout){
        ScheduledFuture future = run.getDispatcher().getScheduler().schedule(()->{
                toRun.doRun(""+timeout,new SyncContext(
                    this.getSession(),
                    this.getState(),
                    this.getRun(),
                    this.getContextTimer(),
                    toRun,
                   this
                ));
        },timeout,TimeUnit.MILLISECONDS);
        timeouts.add(new TimerEntry(toWatch,future,toRun));
    }

    @Override
    public void run() {
        Cmd cmd = getCurrentCmd();
        Cmd previous = cmd !=null ? cmd.getPrevious() : null;
        String input = previous != null ? previous.getOutput() : "";
        run(cmd,input);
    }
    public void run(Cmd cmd,String input){
        if (cmd == null) {
            observerDone();//this context is finished
        } else {
            observerPreStart(cmd);
            cmdTimer = getContextTimer().start(Cmd.populateStateVariables(cmd.toString(),cmd,this));
            if (!lineQueue.isEmpty()) {//clear any unhandled output lines
                //TODO log that we are clearing orphaned lines
                //need to make sure we don't clear if another thread needs to pickup up the CLOSE_QUEUE
                try{
                    lineQueueSemaphore.acquire();
                    lineQueue.clear();
                } catch (InterruptedException e) {
                    System.out.printf("Interrupted cmd=%s%n",cmd.toString());
                    e.printStackTrace();
                } finally {
                    lineQueueSemaphore.release();
                }

            }
            long timestamp = System.currentTimeMillis();
            setStartTime(timestamp);
            setUpdateTime(timestamp);
            //TODO need to only queue signal watchers the first time loopCmd
            if (cmd.hasSignalWatchers()){
                Supplier<String> inputSupplier = ()->getSession().peekOutput();
                for(String name : cmd.getSignalNames()){
                    String populatedName = null;
                    try {
                        populatedName = StringUtil.populatePattern(name,new PatternValuesMap(cmd,this,null));
                    } catch (PopulatePatternException e) {
                        logger.warn(e.getMessage());
                        populatedName = "";
                    }
                    List<Cmd> toCall = cmd.getSignal(name);
                    Cmd root = new ActiveCheckCmd(getCurrentCmd());
                    SyncContext syncContext = new SyncContext(this.getSession(),this.getState(),this.getRun(),this.getContextTimer(),root,this);
                    toCall.forEach(root::then);
                    signalCmds.put(name,root);
                    getCoordinator().waitFor(populatedName,root,syncContext,inputSupplier);
                }
            }
            //timeouts won't be empty if cmd is a loopCmd that already queued timeouts
            if (cmd.hasTimers() && timeouts.isEmpty()) {
                for (Long timeout : cmd.getTimeouts()) {
                    List<Cmd> toCall = cmd.getTimers(timeout);
                    Cmd noOp = Cmd.NO_OP(""+timeout);
                    noOp.setStateParent(cmd);
                    toCall.forEach(noOp::then);
                    //considering adding
                    Cmd activeCmd = new ActiveCheckCmd(cmd);
                    activeCmd.setStateParent(cmd);
                    toCall.forEach(activeCmd::then
                    );
                    //
                    addTimer(
                        cmd,
                        activeCmd,/*activeCmd,/*noOp,*/
                        timeout
                    );

                }
            }
            if (cmd.hasWatchers()) {
                String line = "";
                try {
                    getContextTimer().start("watch.acquire:"+cmd.toString());
                    lineQueueSemaphore.acquire();
                    getContextTimer().start("watch.start:"+cmd.toString());
                    assert lineQueueSemaphore.availablePermits() == 0;

                    cmd.doRun(input, this);
                    while (!CLOSE_QUEUE.equals(line = lineQueue.take())) {
                        logger.trace("watch.line: {}",line);
                        for (Cmd watcher : cmd.getWatchers()) {
                            SyncContext watcherContext = new SyncContext(
                               this.getSession(),
                               this.getState(),
                               this.getRun(),
                               this.getContextTimer(),
                               cmd,
                               this
                            );
                            try {

                                logger.trace("watcher.run {}",watcher);
                                watcherContext.forceCurrentCmd(watcher);
                                watcher.doRun(line, watcherContext);
                            } catch (Exception e) {
                                logger.warn("Exception from watcher " + watcher + "\n  currentCmd=" + watcherContext.getCurrentCmd(), e);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    getContextTimer().start("watch.release:"+cmd.toString());
                    lineQueueSemaphore.release();
                    assert lineQueueSemaphore.availablePermits() == 1;
                }
            } else {
                cmd.doRun(input, this);
            }
        }
    }
}
