package com.graphhopper.reader.postgis;

public class Utils {

    public static RuntimeException asUnchecked(Throwable e) {
        if (RuntimeException.class.isInstance(e)) {
            return (RuntimeException) e;
        }
        return new RuntimeException(e);
    }

}