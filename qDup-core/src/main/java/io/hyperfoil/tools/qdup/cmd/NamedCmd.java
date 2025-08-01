package io.hyperfoil.tools.qdup.cmd;

public abstract class NamedCmd extends Cmd{


    private final String name;

    public NamedCmd(String name){
        this.name = name;
    }

    public String getName(){return name;}
}
