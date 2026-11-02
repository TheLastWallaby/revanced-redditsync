Hand-written Smali models of the Sync 23033 methods "Sync: skip retired media hosts"
edits or calls: the link classifiers in `LinkTypeHelper` (`y7.c`), the Imgur album
check (`f8.a`), the empty-text check (`wc.m.a`), the post-type helper
(`SyncTypeHelper.h`, `c8.e.h`), the image-URL helper (`ImageHelper.b`, `c8.a.b`) and
the DeviantArt, Eroshare and Tumblr request constructors (`r8.a`, `r8.c`, `r8.j`).
They keep the names, signatures, register layouts and strings the patch checks, but
are not the original method bodies.

`RetiredMediaPatchTest` executes them before and after the patch. Its optional APK
argument runs the same checks on the real methods of a local stock Sync 23033 APK
without making or signing an APK.
