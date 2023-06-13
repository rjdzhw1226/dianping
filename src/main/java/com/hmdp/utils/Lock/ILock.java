package com.hmdp.utils.Lock;

public interface ILock {

    public boolean tryLock(long timeoutseconds);

    public void unlock();
}
