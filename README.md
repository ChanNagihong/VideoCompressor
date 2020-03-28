# VideoCompressor
Android video compressor.   
2020/03/29 - Deprecated, I suggest you try https://github.com/natario1/Transcoder
2020/03/26 - Version 1.0.1, works on Android Q from now on.

### Gradle  
```Gradle
compile 'com.nagihong:videocompressor:1.0.0'
```  

* compress video by transcoding  
* reduce video file size to about 8% - 12%  
* transcoding pure by MediaCodec  
* output video can play on iOS  
* currently only support file path argument   
* output video format : "video/avc"  
* output audio format : "audio/mp4a-latm"  
* support api >= 18  

### Usage  
```Java  
new VideoCompressor().compressVideo(inputPath, outputPath);
```  

