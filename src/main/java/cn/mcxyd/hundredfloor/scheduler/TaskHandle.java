package cn.mcxyd.hundredfloor.scheduler;

public interface TaskHandle {

    void cancel();

    boolean cancelled();
}
