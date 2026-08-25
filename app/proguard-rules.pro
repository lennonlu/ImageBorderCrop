# Add project specific ProGuard rules here.
# GifProcessor clears this nullable field between frames so opaque frames following transparent
# frames do not inherit a transparent palette color. The GIF dependency is pinned to 4.16.0.
-keepclassmembers class com.bumptech.glide.gifencoder.AnimatedGifEncoder {
    java.lang.Integer transparent;
}
