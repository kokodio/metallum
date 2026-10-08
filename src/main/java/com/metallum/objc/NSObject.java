package com.metallum.objc;

import java.lang.foreign.MemorySegment;

public abstract class NSObject {
    protected MemorySegment handle;

    protected NSObject(final MemorySegment handle) {
        this.handle = handle;
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public void retain() {
        ObjC.retain(this.handle);
    }

    public void release() {
        ObjC.release(this.handle);
    }
}
