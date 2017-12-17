# VideoCompressor
Android video compressor

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

