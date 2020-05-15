package org.pacemaker.controller;

public interface Controller<T,K> {

    K fetchStatus(T value);
    T updateStatus(T value, K status);
    T control(T value);
}
