package kr.safetypad.monitor.common;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {}
