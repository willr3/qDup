package io.hyperfoil.tools.qdup.config;

import io.hyperfoil.tools.qdup.Env;
import io.hyperfoil.tools.qdup.Host;
import io.hyperfoil.tools.qdup.Stage;
import io.hyperfoil.tools.qdup.cmd.impl.ScriptCmd;
import io.hyperfoil.tools.yaup.HashedLists;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Role {

    private String name;
    private HostExpression hostExpression;
    private Set<String> hostRefs;
    private List<Host> hosts;
    private Map<Host,Env> environments;
    private HashedLists<Stage,ScriptCmd> scripts;

    public Role(){
        this("");
    }
    public Role(String name){
        this.name = name;
        this.hostExpression=null;
        this.hostRefs = new HashSet<>();
        this.hosts = new ArrayList<>();
        this.environments = new ConcurrentHashMap<>();
        this.scripts = new HashedLists<>();
    }

    public List<ScriptCmd> getStage(Stage stage){
        return Collections.unmodifiableList(scripts.get(stage));
    }


    public boolean hasHostExpression(){return hostExpression!=null;}
    public void setHostExpression(HostExpression expression){
        this.hostExpression = expression;
    }
    public HostExpression getHostExpression(){return hostExpression;}

    public boolean hasEnvironment(Host host){
        return environments.containsKey(host);
    }
    public Env getEnv(Host host){
        return environments.get(host);
    }
    public void addEnv(Host host,Env env){
        environments.put(host,env);
    }

    public String getName(){
        return name;
    }
    public List<ScriptCmd> getSetup(){return getStage(Stage.Setup);}
    public List<ScriptCmd> getRun(){return getStage(Stage.Run);}
    public List<ScriptCmd> getCleanup(){return getStage(Stage.Cleanup);}
    public boolean hasScripts(){
        return !scripts.isEmpty();
    }

    /**
     * @return Hosts that are listed in the role. Empty if the role uses an expression
     */
    public List<Host> getDeclaredHosts(){
        return Collections.unmodifiableList(hosts);
    }

    /**
     * Get the hosts used by this role.
     * @param runConfig the RunConfig to resolve role references
     * @return all hosts that will be used for this role
     */
    public List<Host> getHosts(RunConfig runConfig) {
        if (hasHostExpression()) {
            return Collections.unmodifiableList(hostExpression.getHosts(runConfig));
        } else {
            return getDeclaredHosts();
        }
    }

    public void addSetup(ScriptCmd script){
        this.scripts.put(Stage.Setup,script);
    }
    public void addRun(ScriptCmd script){
        this.scripts.put(Stage.Run,script);
    }
    public void addCleanup(ScriptCmd script){
        this.scripts.put(Stage.Cleanup,script);
    }
    public void addHost(Host host){
        this.hosts.add(host);
    }
    public void addHostRef(String name){
        this.hostRefs.add(name);
    }
    public boolean hasHostRefs(){return !hostRefs.isEmpty();}
    public Set<String> getHostRefs(){return Collections.unmodifiableSet(hostRefs);}



}
