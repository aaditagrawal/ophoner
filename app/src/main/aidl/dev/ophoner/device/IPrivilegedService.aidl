package dev.ophoner.device;
interface IPrivilegedService {
    void destroy() = 16777114;
    String[] execute(String command, long timeoutMs) = 1;
    void cancelAll() = 2;
}
