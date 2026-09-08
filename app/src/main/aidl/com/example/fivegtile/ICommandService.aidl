package com.example.fivegtile;

interface ICommandService {
    void destroy() = 16777114;
    String exec(String command) = 1;
    int uid() = 2;
}
