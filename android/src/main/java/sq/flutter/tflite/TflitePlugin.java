package sq.flutter.tflite;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Vector;


public class TflitePlugin implements MethodCallHandler {
  private final Registrar mRegistrar;
  private Interpreter tfLite;
  private boolean tfLiteBusy = false;
  private int inputSize = 0;
  private Vector<String> labels;
  float[][] labelProb;
  private static final int BYTES_PER_CHANNEL = 4;

  public static void registerWith(Registrar registrar) {
    final MethodChannel channel = new MethodChannel(registrar.messenger(), "tflite");
    channel.setMethodCallHandler(new TflitePlugin(registrar));
  }

  private TflitePlugin(Registrar registrar) {
    this.mRegistrar = registrar;
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    if (call.method.equals("loadModel")) {
      try {
        String res = loadModel((HashMap) call.arguments);
        result.success(res);
      }
      catch (Exception e) {
        result.error("Failed to load model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runModelOnImage")) {
      try {
        new RunModelOnImage((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runModelOnBinary")) {
      try {
        new RunModelOnBinary((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runModelOnFrame")) {
      try {
        new RunModelOnFrame((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("detectObjectOnImage")) {
      try {
        detectObjectOnImage((HashMap) call.arguments, result);
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("detectObjectOnBinary")) {
      try {
        detectObjectOnBinary((HashMap) call.arguments, result);
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("detectObjectOnFrame")) {
      try {
        detectObjectOnFrame((HashMap) call.arguments, result);
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("close")) {
      close();
    } else if (call.method.equals("runPix2PixOnImage")) {
      try {
        new RunPix2PixOnImage((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runPix2PixOnBinary")) {
      try {
        new RunPix2PixOnBinary((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runPix2PixOnFrame")) {
      try {
        new RunPix2PixOnFrame((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runSegmentationOnImage")) {
      try {
        new RunSegmentationOnImage((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runSegmentationOnBinary")) {
      try {
        new RunSegmentationOnBinary((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else if (call.method.equals("runSegmentationOnFrame")) {
      try {
        new RunSegmentationOnFrame((HashMap) call.arguments, result).executeTfliteTask();
      }
      catch (Exception e) {
        result.error("Failed to run model" , e.getMessage(), e);
      }
    } else {
      result.error("Invalid method", call.method.toString(), "");
    }
  }

  private String loadModel(HashMap args) throws IOException {
    String model = args.get("model").toString();
    AssetManager assetManager = mRegistrar.context().getAssets();
    String key = mRegistrar.lookupKeyForAsset(model);
    AssetFileDescriptor fileDescriptor = assetManager.openFd(key);
    FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
    FileChannel fileChannel = inputStream.getChannel();
    long startOffset = fileDescriptor.getStartOffset();
    long declaredLength = fileDescriptor.getDeclaredLength();
    MappedByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

    int numThreads = (int)args.get("numThreads");
    final Interpreter.Options tfliteOptions = new Interpreter.Options();
    tfliteOptions.setNumThreads(numThreads);
    tfLite = new Interpreter(buffer, tfliteOptions);

    String labels = args.get("labels").toString();
    key = mRegistrar.lookupKeyForAsset(labels);
    loadLabels(assetManager, key);

    return "success";
  }

  private void loadLabels(AssetManager assetManager, String path) {
    BufferedReader br;
    try {
      br = new BufferedReader(new InputStreamReader(assetManager.open(path)));
      String line;
      labels = new Vector<>();
      while ((line = br.readLine()) != null) {
        labels.add(line);
      }
      labelProb = new float[1][labels.size()];
      br.close();
    } catch (IOException e) {
      throw new RuntimeException("Failed to read label file" , e);
    }
  }

  private List<Map<String, Object>> GetTopN(int numResults, float threshold) {
    PriorityQueue<Map<String, Object>> pq =
        new PriorityQueue<>(
            1,
            new Comparator<Map<String, Object>>() {
              @Override
              public int compare(Map<String, Object> lhs, Map<String, Object> rhs) {
                return Float.compare((float)rhs.get("confidence"), (float)lhs.get("confidence"));
              }
            });

    for (int i = 0; i < labels.size(); ++i) {
      float confidence = labelProb[0][i];
      if (confidence > threshold) {
        Map<String, Object> res = new HashMap<>();
        res.put("index", i);
        res.put("label", labels.size() > i ? labels.get(i) : "unknown");
        res.put("confidence", confidence);
        pq.add(res);
      }
    }

    final ArrayList<Map<String, Object>> recognitions = new ArrayList<>();
    int recognitionsSize = Math.min(pq.size(), numResults);
    for (int i = 0; i < recognitionsSize; ++i) {
      recognitions.add(pq.poll());
    }

    return recognitions;
  }

  Bitmap feedOutput(ByteBuffer imgData, float mean, float std) {
    Tensor tensor = tfLite.getOutputTensor(0);
    int outputSize = tensor.shape()[1];
    Bitmap bitmapRaw = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888);

    if (tensor.dataType() == DataType.FLOAT32) {
      for (int i = 0; i < outputSize; ++i) {
        for (int j = 0; j < outputSize; ++j) {
          int pixelValue = 0xFF << 24;
          pixelValue |= ((Math.round(imgData.getFloat() * std + mean) & 0xFF) << 16);
          pixelValue |= ((Math.round(imgData.getFloat() * std + mean) & 0xFF) << 8);
          pixelValue |= ((Math.round(imgData.getFloat() * std + mean) & 0xFF));
          bitmapRaw.setPixel(j, i, pixelValue);
        }
      }
    } else {
      for (int i = 0; i < outputSize; ++i) {
        for (int j = 0; j < outputSize; ++j) {
          int pixelValue = 0xFF << 24;
          pixelValue |= ((imgData.get() & 0xFF) << 16);
          pixelValue |= ((imgData.get() & 0xFF) << 8);
          pixelValue |= ((imgData.get() & 0xFF));
          bitmapRaw.setPixel(j, i, pixelValue);
        }
      }
    }
    return bitmapRaw;
  }

  ByteBuffer feedInputTensor(Bitmap bitmapRaw, float mean, float std) throws IOException {
    Tensor tensor = tfLite.getInputTensor(0);
    inputSize = tensor.shape()[1];
    int inputChannels = tensor.shape()[3];

    int bytePerChannel = tensor.dataType() == DataType.UINT8 ? 1 : BYTES_PER_CHANNEL;
    ByteBuffer imgData = ByteBuffer.allocateDirect(1 * inputSize * inputSize * inputChannels * bytePerChannel);
    imgData.order(ByteOrder.nativeOrder());

    Bitmap bitmap = bitmapRaw;
    if (bitmapRaw.getWidth() != inputSize || bitmapRaw.getHeight() != inputSize) {
      Matrix matrix = getTransformationMatrix(bitmapRaw.getWidth(), bitmapRaw.getHeight(),
                                              inputSize, inputSize, false);
      bitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888);
      final Canvas canvas = new Canvas(bitmap);
      canvas.drawBitmap(bitmapRaw, matrix, null);
    }

    if (tensor.dataType() == DataType.FLOAT32) {
      for (int i = 0; i < inputSize; ++i) {
        for (int j = 0; j < inputSize; ++j) {
          int pixelValue = bitmap.getPixel(j, i);
          imgData.putFloat((((pixelValue >> 16) & 0xFF) - mean) / std);
          imgData.putFloat((((pixelValue >> 8) & 0xFF) - mean) / std);
          imgData.putFloat(((pixelValue & 0xFF) - mean) / std);
        }
      }
    } else {
      for (int i = 0; i < inputSize; ++i) {
        for (int j = 0; j < inputSize; ++j) {
          int pixelValue = bitmap.getPixel(j, i);
          imgData.put((byte)((pixelValue >> 16) & 0xFF));
          imgData.put((byte)((pixelValue >> 8) & 0xFF));
          imgData.put((byte)(pixelValue & 0xFF));
        }
      }
    }

    return imgData;
  }

  public Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
    int width = bm.getWidth();
    int height = bm.getHeight();
    float scaleWidth = ((float) newWidth) / width;
    float scaleHeight = ((float) newHeight) / height;
    // CREATE A MATRIX FOR THE MANIPULATION
    Matrix matrix = new Matrix();
    // RESIZE THE BIT MAP
    matrix.postScale(scaleWidth, scaleHeight);

    // "RECREATE" THE NEW BITMAP
    Bitmap resizedBitmap = Bitmap.createBitmap(
            bm, 0, 0, width, height, matrix, false);
    return resizedBitmap;
  }

  ByteBuffer feedInputTensorImage(String path, float mean, float std, int newWidth, int newHeight) throws IOException {
    InputStream inputStream = new FileInputStream(path.replace("file://",""));
    Bitmap bitmapRaw;
    if (newWidth == 0 && newHeight == 0) {
      bitmapRaw = BitmapFactory.decodeStream(inputStream);
    } else {
      bitmapRaw = Bitmap.createScaledBitmap(BitmapFactory.decodeStream(inputStream), newWidth, newHeight, true);
    }

    return feedInputTensor(bitmapRaw, mean, std);
  }

  ByteBuffer feedInputTensorImage(String path, float mean, float std) throws IOException {
    return feedInputTensorImage(path, mean, std, 0 , 0);
  }

  ByteBuffer feedInputTensorFrame(List<byte[]> bytesList, int imageHeight, int imageWidth, float mean, float std, int rotation) throws IOException {
    ByteBuffer Y = ByteBuffer.wrap(bytesList.get(0));
    ByteBuffer U = ByteBuffer.wrap(bytesList.get(1));
    ByteBuffer V = ByteBuffer.wrap(bytesList.get(2));

    int Yb = Y.remaining();
    int Ub = U.remaining();
    int Vb = V.remaining();

    byte[] data = new byte[Yb + Ub + Vb];

    Y.get(data, 0, Yb);
    V.get(data, Yb, Vb);
    U.get(data, Yb + Vb, Ub);

    Bitmap bitmapRaw = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
    Allocation bmData = renderScriptNV21ToRGBA888(
        mRegistrar.context(),
        imageWidth,
        imageHeight,
        data);
    bmData.copyTo(bitmapRaw);

    Matrix matrix = new Matrix();
    matrix.postRotate(rotation);
    bitmapRaw = Bitmap.createBitmap(bitmapRaw, 0, 0, bitmapRaw.getWidth(), bitmapRaw.getHeight(), matrix, true);

    return feedInputTensor(bitmapRaw, mean, std);
  }

  public Allocation renderScriptNV21ToRGBA888(Context context, int width, int height, byte[] nv21) {
    // https://stackoverflow.com/a/36409748
    RenderScript rs = RenderScript.create(context);
    ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));

    Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(nv21.length);
    Allocation in = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);

    Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height);
    Allocation out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);

    in.copyFrom(nv21);

    yuvToRgbIntrinsic.setInput(in);
    yuvToRgbIntrinsic.forEach(out);
    return out;
  }

  private abstract class TfliteTask extends AsyncTask<Void, Void, Void> {
    Result result;
    boolean asynch;

    TfliteTask(HashMap args, Result result) {
      if (tfLiteBusy) throw new RuntimeException("Interpreter busy");
      else tfLiteBusy = true;
      Object asynch = args.get("asynch");
      this.asynch = asynch == null ? false : (boolean)asynch;
      this.result = result;
    }

    abstract void runTflite();

    abstract void onRunTfliteDone();

    public void executeTfliteTask() {
      if (asynch) execute();
      else {
        runTflite();
        tfLiteBusy = false;
        onRunTfliteDone();
      }
    }

    protected Void doInBackground(Void... backgroundArguments) {
      runTflite();
      return null;
    }

    protected void onPostExecute(Void backgroundResult) {
      tfLiteBusy = false;
      onRunTfliteDone();
    }
  }

  private class RunModelOnImage extends TfliteTask {
    int NUM_RESULTS;
    float THRESHOLD;
    ByteBuffer input;
    long startTime;

    RunModelOnImage(HashMap args, Result result) throws IOException {
      super(args, result);

      String path = args.get("path").toString();
      double mean = (double)(args.get("imageMean"));
      float IMAGE_MEAN = (float)mean;
      double std = (double)(args.get("imageStd"));
      float IMAGE_STD = (float)std;
      NUM_RESULTS = (int)args.get("numResults");
      double threshold = (double)args.get("threshold");
      THRESHOLD = (float)threshold;

      startTime = SystemClock.uptimeMillis();
      input = feedInputTensorImage(path, IMAGE_MEAN, IMAGE_STD);
    }

    protected void runTflite() { tfLite.run(input, labelProb); }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));
      result.success(GetTopN(NUM_RESULTS, THRESHOLD));
    }
  }

  private class RunModelOnBinary extends TfliteTask {
    int NUM_RESULTS;
    float THRESHOLD;
    ByteBuffer imgData;

    RunModelOnBinary(HashMap args, Result result) throws IOException {
      super(args, result);

      byte[] binary = (byte[])args.get("binary");
      NUM_RESULTS = (int)args.get("numResults");
      double threshold = (double)args.get("threshold");
      THRESHOLD = (float)threshold;

      imgData = ByteBuffer.wrap(binary);
    }

    protected void runTflite() { tfLite.run(imgData, labelProb); }

    protected void onRunTfliteDone() {
      result.success(GetTopN(NUM_RESULTS, THRESHOLD));
    }
  }

  private class RunModelOnFrame extends TfliteTask {
    int NUM_RESULTS;
    float THRESHOLD;
    long startTime;
    ByteBuffer imgData;

    RunModelOnFrame(HashMap args, Result result) throws IOException {
      super(args, result);

      List<byte[]> bytesList= (ArrayList)args.get("bytesList");
      double mean = (double)(args.get("imageMean"));
      float IMAGE_MEAN = (float)mean;
      double std = (double)(args.get("imageStd"));
      float IMAGE_STD = (float)std;
      int imageHeight = (int)(args.get("imageHeight"));
      int imageWidth = (int)(args.get("imageWidth"));
      int rotation = (int)(args.get("rotation"));
      NUM_RESULTS = (int)args.get("numResults");
      double threshold = (double)args.get("threshold");
      THRESHOLD = (float)threshold;

      startTime = SystemClock.uptimeMillis();

      imgData = feedInputTensorFrame(bytesList, imageHeight, imageWidth, IMAGE_MEAN, IMAGE_STD, rotation);
    }

    protected void runTflite() { tfLite.run(imgData, labelProb); }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));
      result.success(GetTopN(NUM_RESULTS, THRESHOLD));
    }
  }

  void detectObjectOnImage(HashMap args, Result result) throws IOException {
    String path = args.get("path").toString();
    String model = args.get("model").toString();
    double mean = (double)(args.get("imageMean"));
    float IMAGE_MEAN = (float)mean;
    double std = (double)(args.get("imageStd"));
    float IMAGE_STD = (float)std;
    double threshold = (double)args.get("threshold");
    float THRESHOLD = (float)threshold;
    int BLOCK_SIZE = (int)args.get("blockSize");
    int NUM_BOXES_PER_BLOCK = (int)args.get("numBoxesPerBlock");
    int NUM_RESULTS_PER_CLASS = (int)args.get("numResultsPerClass");

    int newWidth = 0;
    int newHeight = 0;

    if (model.equals("YOLOv3")) {
      newWidth = 416;
      newHeight = 416;
    }

    ByteBuffer imgData = feedInputTensorImage(path, IMAGE_MEAN, IMAGE_STD, newWidth, newHeight);

    if (model.equals("SSDMobileNet")) {
      new RunSSDMobileNet(args, imgData, NUM_RESULTS_PER_CLASS, THRESHOLD, result).executeTfliteTask();
    } else if (model.equals("YOLO")) {
      List<Double> ANCHORS = (ArrayList)args.get("anchors");
      new RunYOLO(args, imgData, BLOCK_SIZE, NUM_BOXES_PER_BLOCK, ANCHORS, THRESHOLD, NUM_RESULTS_PER_CLASS, result).executeTfliteTask();
    } else if (model.equals("YOLOv3")) {
      List<List<Integer>> ANCHORS = (ArrayList)args.get("anchors");
      new RunYOLOv3(args, imgData, BLOCK_SIZE, NUM_BOXES_PER_BLOCK, ANCHORS, THRESHOLD, NUM_RESULTS_PER_CLASS, result).executeTfliteTask();
    }
  }

  void detectObjectOnBinary(HashMap args, Result result) throws IOException {
    byte[] binary = (byte[])args.get("binary");
    String model = args.get("model").toString();
    double threshold = (double)args.get("threshold");
    float THRESHOLD = (float)threshold;
    List<Double> ANCHORS = (ArrayList)args.get("anchors");
    int BLOCK_SIZE = (int)args.get("blockSize");
    int NUM_BOXES_PER_BLOCK = (int)args.get("numBoxesPerBlock");
    int NUM_RESULTS_PER_CLASS = (int)args.get("numResultsPerClass");

    ByteBuffer imgData = ByteBuffer.wrap(binary);

    if (model.equals("SSDMobileNet")) {
      new RunSSDMobileNet(args, imgData, NUM_RESULTS_PER_CLASS, THRESHOLD, result).executeTfliteTask();
    } else {
      new RunYOLO(args, imgData, BLOCK_SIZE, NUM_BOXES_PER_BLOCK, ANCHORS, THRESHOLD, NUM_RESULTS_PER_CLASS, result).executeTfliteTask();
    }
  }

  void detectObjectOnFrame(HashMap args, Result result) throws IOException {
    List<byte[]> bytesList= (ArrayList)args.get("bytesList");
    String model = args.get("model").toString();
    double mean = (double)(args.get("imageMean"));
    float IMAGE_MEAN = (float)mean;
    double std = (double)(args.get("imageStd"));
    float IMAGE_STD = (float)std;
    int imageHeight = (int)(args.get("imageHeight"));
    int imageWidth = (int)(args.get("imageWidth"));
    int rotation = (int)(args.get("rotation"));
    double threshold = (double)args.get("threshold");
    float THRESHOLD = (float)threshold;
    int NUM_RESULTS_PER_CLASS = (int)args.get("numResultsPerClass");

    List<Double> ANCHORS = (ArrayList)args.get("anchors");
    int BLOCK_SIZE = (int)args.get("blockSize");
    int NUM_BOXES_PER_BLOCK = (int)args.get("numBoxesPerBlock");

    ByteBuffer imgData = feedInputTensorFrame(bytesList, imageHeight, imageWidth, IMAGE_MEAN, IMAGE_STD, rotation);

    if (model.equals("SSDMobileNet")) {
      new RunSSDMobileNet(args, imgData, NUM_RESULTS_PER_CLASS, THRESHOLD, result).executeTfliteTask();
    } else {
      new RunYOLO(args, imgData, BLOCK_SIZE, NUM_BOXES_PER_BLOCK, ANCHORS, THRESHOLD, NUM_RESULTS_PER_CLASS, result).executeTfliteTask();
    }
  }

  private class RunPix2PixOnImage extends TfliteTask {
    String path, outputType;
    float IMAGE_MEAN, IMAGE_STD;
    long startTime;
    ByteBuffer input, output;

    RunPix2PixOnImage(HashMap args, Result result) throws IOException {
      super(args, result);
      path = args.get("path").toString();
      double mean = (double)(args.get("imageMean"));
      IMAGE_MEAN = (float)mean;
      double std = (double)(args.get("imageStd"));
      IMAGE_STD = (float)std;

      outputType = args.get("outputType").toString();
      startTime = SystemClock.uptimeMillis();
      input = feedInputTensorImage(path, IMAGE_MEAN, IMAGE_STD);
      output = ByteBuffer.allocateDirect(input.limit());
      output.order(ByteOrder.nativeOrder());
      if (input.limit() == 0) { result.error("Unexpected input position, bad file?", null, null); return; }
      if (output.position() != 0) { result.error("Unexpected output position", null, null); return; }
    }

    protected void runTflite() { tfLite.run(input, output); }

    protected void onRunTfliteDone() {
      Log.v("time", "Generating took " + (SystemClock.uptimeMillis() - startTime));
      if (output.position() != input.limit()) { result.error("Mismatching input/output position", null, null); return; }

      output.flip();
      Bitmap bitmapRaw = feedOutput(output, IMAGE_MEAN, IMAGE_STD);

      if (outputType.equals("png")) {
        result.success(compressPNG(bitmapRaw));
      } else {
        result.success(bitmapRaw);
      }
    }
  }

  private class RunPix2PixOnBinary extends TfliteTask {
    long startTime;
    String outputType;
    ByteBuffer input, output;

    RunPix2PixOnBinary(HashMap args, Result result) throws IOException {
      super(args, result);
      byte[] binary = (byte[])args.get("binary");
      outputType = args.get("outputType").toString();
      startTime = SystemClock.uptimeMillis();
      input = ByteBuffer.wrap(binary);
      output = ByteBuffer.allocateDirect(input.limit());
      output.order(ByteOrder.nativeOrder());

      if (input.limit() == 0) { result.error("Unexpected input position, bad file?", null, null); return; }
      if (output.position() != 0) { result.error("Unexpected output position", null, null); return; }
    }

    protected void runTflite() { tfLite.run(input, output); }

    protected void onRunTfliteDone() {
      Log.v("time", "Generating took " + (SystemClock.uptimeMillis() - startTime));
      if (output.position() != input.limit()) { result.error("Mismatching input/output position", null, null); return; }

      output.flip();
      result.success(output.array());
    }
  }

  private class RunPix2PixOnFrame extends TfliteTask {
    long startTime;
    String outputType;
    float IMAGE_MEAN, IMAGE_STD;
    ByteBuffer input, output;

    RunPix2PixOnFrame(HashMap args, Result result) throws IOException {
      super(args, result);
      List<byte[]> bytesList= (ArrayList)args.get("bytesList");
      double mean = (double)(args.get("imageMean"));
      IMAGE_MEAN = (float)mean;
      double std = (double)(args.get("imageStd"));
      IMAGE_STD = (float)std;
      int imageHeight = (int)(args.get("imageHeight"));
      int imageWidth = (int)(args.get("imageWidth"));
      int rotation = (int)(args.get("rotation"));

      outputType = args.get("outputType").toString();
      startTime = SystemClock.uptimeMillis();
      input = feedInputTensorFrame(bytesList, imageHeight, imageWidth, IMAGE_MEAN, IMAGE_STD, rotation);
      output = ByteBuffer.allocateDirect(input.limit());
      output.order(ByteOrder.nativeOrder());

      if (input.limit() == 0) { result.error("Unexpected input position, bad file?", null, null); return; }
      if (output.position() != 0) { result.error("Unexpected output position", null, null); return; }
    }

    protected void runTflite() { tfLite.run(input, output); }

    protected void onRunTfliteDone() {
      Log.v("time", "Generating took " + (SystemClock.uptimeMillis() - startTime));
      if (output.position() != input.limit()) { result.error("Mismatching input/output position", null, null); return; }

      output.flip();
      Bitmap bitmapRaw = feedOutput(output, IMAGE_MEAN, IMAGE_STD);

      if (outputType.equals("png")) {
        result.success(compressPNG(bitmapRaw));
      } else {
        result.success(bitmapRaw);
      }
    }
  }

  private class RunSSDMobileNet extends TfliteTask {
    int num;
    int numResultsPerClass;
    float threshold;
    float[][][] outputLocations;
    float[][] outputClasses;
    float[][] outputScores;
    float[] numDetections = new float[1];
    Object[] inputArray;
    Map<Integer, Object> outputMap = new HashMap<>();
    long startTime;

    RunSSDMobileNet(HashMap args, ByteBuffer imgData, int numResultsPerClass, float threshold, Result result) {
      super(args, result);
      this.num = tfLite.getOutputTensor(0).shape()[1];
      this.numResultsPerClass = numResultsPerClass;
      this.threshold = threshold;
      this.outputLocations = new float[1][num][4];
      this.outputClasses = new float[1][num];
      this.outputScores = new float[1][num];
      this.inputArray = new Object[] {imgData};

      outputMap.put(0, outputLocations);
      outputMap.put(1, outputClasses);
      outputMap.put(2, outputScores);
      outputMap.put(3, numDetections);

      startTime = SystemClock.uptimeMillis();
    }

    protected void runTflite() { tfLite.runForMultipleInputsOutputs(inputArray, outputMap); }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));

      Map<String, Integer> counters = new HashMap<>();
      final List<Map<String, Object>> results = new ArrayList<>();
  
      for (int i = 0; i < numDetections[0]; ++i) {
        if (outputScores[0][i] < threshold) continue;

        String detectedClass = labels.get((int) outputClasses[0][i] + 1);

        if (counters.get(detectedClass) == null) {
          counters.put(detectedClass, 1);
        } else {
          int count = counters.get(detectedClass);
          if (count >= numResultsPerClass) {
            continue;
          } else {
            counters.put(detectedClass, count + 1);
          }
        }

        Map<String, Object> rect = new HashMap<>();
        float ymin = Math.max(0, outputLocations[0][i][0]);
        float xmin = Math.max(0, outputLocations[0][i][1]);
        float ymax = outputLocations[0][i][2];
        float xmax = outputLocations[0][i][3];
        rect.put("x", xmin);
        rect.put("y", ymin);
        rect.put("w", Math.min(1 - xmin, xmax - xmin));
        rect.put("h", Math.min(1 - ymin, ymax - ymin));

        Map<String, Object> ret = new HashMap<>();
        ret.put("rect", rect);
        ret.put("confidenceInClass", outputScores[0][i]);
        ret.put("detectedClass", detectedClass);

        results.add(ret);
      }

      result.success(results);
    }
  }

  private class RunYOLO extends TfliteTask {
    ByteBuffer imgData;
    int blockSize;
    int numBoxesPerBlock;
    List<Double> anchors;
    float threshold;
    int numResultsPerClass;
    long startTime;
    int gridSize;
    int numClasses;
    final float[][][][] output;

    RunYOLO(HashMap args,
            ByteBuffer imgData,
            int blockSize,
            int numBoxesPerBlock,
            List<Double> anchors,
            float threshold,
            int numResultsPerClass,
            Result result)
    {
      super(args, result);
      this.imgData = imgData;
      this.blockSize = blockSize;
      this.numBoxesPerBlock = numBoxesPerBlock;
      this.anchors = anchors;
      this.threshold = threshold;
      this.numResultsPerClass = numResultsPerClass;
      this.startTime = SystemClock.uptimeMillis();

      Tensor tensor = tfLite.getInputTensor(0);
      inputSize = tensor.shape()[1];

      this.gridSize = inputSize / blockSize;
      this.numClasses = labels.size();
      this.output = new float[1][gridSize][gridSize][(numClasses + 5) * numBoxesPerBlock];
    }

    protected void runTflite() { tfLite.run(imgData, output); }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));

      PriorityQueue<Map<String, Object>> pq =
          new PriorityQueue<>(
              1,
              new Comparator<Map<String, Object>>() {
                @Override
                public int compare(Map<String, Object> lhs, Map<String, Object> rhs) {
                  return Float.compare((float)rhs.get("confidenceInClass"), (float)lhs.get("confidenceInClass"));
                }
              });

      for (int y = 0; y < gridSize; ++y) {
        for (int x = 0; x < gridSize; ++x) {
          for (int b = 0; b < numBoxesPerBlock; ++b) {
            final int offset = (numClasses + 5) * b;

            final float confidence = expit(output[0][y][x][offset + 4]);

            final float[] classes = new float[numClasses];
            for (int c = 0; c < numClasses; ++c) {
              classes[c] = output[0][y][x][offset + 5 + c];
            }
            softmax(classes);

            int detectedClass = -1;
            float maxClass = 0;
            for (int c = 0; c < numClasses; ++c) {
              if (classes[c] > maxClass) {
                detectedClass = c;
                maxClass = classes[c];
              }
            }

            final float confidenceInClass = maxClass * confidence;
            if (confidenceInClass > threshold) {
              final float xPos = (x + expit(output[0][y][x][offset + 0])) * blockSize;
              final float yPos = (y + expit(output[0][y][x][offset + 1])) * blockSize;

              final float w = (float) (Math.exp(output[0][y][x][offset + 2]) * anchors.get(2 * b + 0)) * blockSize;
              final float h = (float) (Math.exp(output[0][y][x][offset + 3]) * anchors.get(2 * b + 1)) * blockSize;

              final float xmin = Math.max(0, (xPos - w / 2) / inputSize);
              final float ymin = Math.max(0, (yPos - h / 2) / inputSize);

              Map<String, Object> rect = new HashMap<>();
              rect.put("x", xmin);
              rect.put("y", ymin);
              rect.put("w", Math.min(1 - xmin, w / inputSize));
              rect.put("h", Math.min(1 - ymin, h / inputSize));

              Map<String, Object> ret = new HashMap<>();
              ret.put("rect", rect);
              ret.put("confidenceInClass", confidenceInClass);
              ret.put("detectedClass", labels.get(detectedClass));

              pq.add(ret);
            }
          }
        }
      }

      Map<String, Integer> counters = new HashMap<>();
      List<Map<String, Object>> results = new ArrayList<>();

      for (int i = 0; i < pq.size(); ++i) {
        Map<String, Object> ret = pq.poll();
        String detectedClass = ret.get("detectedClass").toString();

        if (counters.get(detectedClass) == null) {
          counters.put(detectedClass, 1);
        } else {
          int count = counters.get(detectedClass);
          if (count >= numResultsPerClass) {
            continue;
          } else {
            counters.put(detectedClass, count + 1);
          }
        }
        results.add(ret);
      }
      result.success(results);
    }
  }

  private class Box {
    float x;
    float y;
    float w;
    float h;
    float confidenceInClass;
    float classValue;
    String detectedClass;
  }

  private class RunYOLOv3 extends TfliteTask {
    ByteBuffer imgData;
    int blockSize;
    int numBoxesPerBlock;
    List<List<Integer>> anchors;
    float threshold;
    int numResultsPerClass;
    long startTime;
    int numClasses;
    final float[][][][] output_0, output_1;
    Map<Integer, Object> outputMap;
    Object[] inputArray;

    RunYOLOv3(HashMap args,
            ByteBuffer imgData,
            int blockSize,
            int numBoxesPerBlock,
            List<List<Integer>> anchors,
            float threshold,
            int numResultsPerClass,
            Result result)
    {
      super(args, result);
      this.imgData = imgData;
      this.blockSize = blockSize;
      this.numBoxesPerBlock = numBoxesPerBlock;
      this.anchors = anchors;
      this.threshold = threshold;
      this.numResultsPerClass = numResultsPerClass;
      this.startTime = SystemClock.uptimeMillis();

      Tensor tensor = tfLite.getInputTensor(0);
      inputSize = tensor.shape()[1];

      this.numClasses = labels.size();
      this.output_0 = new float[1][13][13][(numClasses + 5) * numBoxesPerBlock];
      this.output_1 = new float[1][26][26][(numClasses + 5) * numBoxesPerBlock];
      this.outputMap = new HashMap<>();
      outputMap.put(0, output_0);
      outputMap.put(1, output_1);
      inputArray = new Object[1];
      inputArray[0] = imgData;
    }

    protected void runTflite() {
      tfLite.runForMultipleInputsOutputs(inputArray, outputMap);
    }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));

      PriorityQueue<Box> pq =
              new PriorityQueue<>(
                      1,
                      new Comparator<Box>() {
                        @Override
                        public int compare(Box lhs, Box rhs) {
                          float diff = rhs.confidenceInClass - lhs.confidenceInClass;
                          if (diff > 0) {
                            return 1;
                          } else if (diff < 0) {
                            return  -1;
                          } else {
                            return 0;
                          }
                        }
                      });
      this.yolo_head(output_0, 32, anchors.get(0), numClasses, numBoxesPerBlock, threshold, pq);
      this.yolo_head(output_1, 16, anchors.get(1), numClasses, numBoxesPerBlock, threshold, pq);

      List<Map<String, Object>> results = this.nms(pq, 0.4);

      result.success(results);
    }

    protected void yolo_head(final float[][][][] output, int blockSize, List<Integer> anchors, int numClasses, int numBoxesPerBlock, float threshold, PriorityQueue<Box> pq) {
      int gridSize = inputSize / blockSize;
      for (int y = 0; y < gridSize; ++y) {
        for (int x = 0; x < gridSize; ++x) {
          for (int b = 0; b < numBoxesPerBlock; ++b) {
            final int offset = (numClasses + 5) * b;

            final float confidence = expit(output[0][y][x][offset + 4]);

            final float[] classes = new float[numClasses];
            for (int c = 0; c < numClasses; ++c) {
              classes[c] = output[0][y][x][offset + 5 + c];
            }

            int detectedClass = -1;
            float maxClass = 0;
            for (int c = 0; c < numClasses; ++c) {
              float weight = expit(classes[c]);
              if (weight > maxClass) {
                detectedClass = c;
                maxClass = weight;
              }
            }

            final float confidenceInClass = maxClass * confidence;
            if (confidenceInClass > threshold) {
              final float xPos = (x + expit(output[0][y][x][offset + 0])) * blockSize;
              final float yPos = (y + expit(output[0][y][x][offset + 1])) * blockSize;

              final float w = (float) (Math.exp(output[0][y][x][offset + 2]) * anchors.get(2 * b + 0));
              final float h = (float) (Math.exp(output[0][y][x][offset + 3]) * anchors.get(2 * b + 1));

              final float xmin = Math.max(0, (xPos - w / 2) / inputSize);
              final float ymin = Math.max(0, (yPos - h / 2) / inputSize);

              Box box = new Box();
              box.x = xmin;
              box.y = ymin;
              box.w = Math.min(inputSize - xmin, w / inputSize);
              box.h = Math.min(inputSize - ymin, h / inputSize);
              box.confidenceInClass = confidenceInClass;
              box.detectedClass = labels.get(detectedClass);
              box.classValue = maxClass;

              pq.add(box);
            }
          }
        }
      }
    }

    private List<Map<String, Object>> nms(PriorityQueue<Box> pq, double iouThreshold) {
      List<Map<String, Object>> outputBoxList = new ArrayList<>();

      while (!pq.isEmpty()) {
        Box topBox = pq.remove();

        Map<String, Object> rect = new HashMap<>();
        rect.put("x", topBox.x);
        rect.put("y", topBox.y);
        rect.put("w", topBox.w);
        rect.put("h", topBox.h);

        Map<String, Object> ret = new HashMap<>();
        ret.put("rect", rect);
        ret.put("confidenceInClass", topBox.confidenceInClass);
        ret.put("detectedClass", topBox.detectedClass);

        outputBoxList.add(ret);


        Iterator<Box> boxIterator = pq.iterator();
        while(boxIterator.hasNext()) {
          Box box = boxIterator.next();
          // Only compare boxes of the same class
          if (box.detectedClass.equals(topBox.detectedClass)) {
            float iou = iou(topBox, box);
            if (iou >= iouThreshold) {
              boxIterator.remove();
            }
          }
        }
      }

      return outputBoxList;
    }

    private float iou(Box boxA, Box boxB) {
      // determine the (x, y)-coordinates of the intersection rectangle
      float boxAx2 = boxA.x + boxA.w;
      float boxAy2 = boxA.y + boxA.h;

      float boxBx2 = boxB.x + boxB.w;
      float boxBy2 = boxB.y + boxB.h;

      float xA = Math.max(boxA.x, boxB.x);
      float yA = Math.max(boxA.y, boxB.y);
      float xB = Math.min(boxAx2, boxBx2);
      float yB = Math.min(boxAy2, boxBy2);

      // compute the area of intersection rectangle
      float interArea = Math.max(0, xB - xA) * Math.max(0, yB - yA);

      // compute the area of both the prediction and ground-truth rectangles
      float boxAArea = boxA.w * boxA.h;
      float boxBArea = boxB.w * boxB.h;

      // compute the intersection over union by taking the intersection
      // area and dividing it by the sum of prediction + ground-truth
      // areas - the interesection area
      float iou = interArea / (boxAArea + boxBArea - interArea);
      // return the intersection over union value
      return iou;
    }
  }

  private class RunSegmentationOnImage extends TfliteTask {
    List<Long> labelColors;
    String outputType;
    long startTime;
    ByteBuffer input, output;

    RunSegmentationOnImage(HashMap args, Result result) throws IOException {
      super(args, result);

      String path = args.get("path").toString();
      double mean = (double)(args.get("imageMean"));
      float IMAGE_MEAN = (float)mean;
      double std = (double)(args.get("imageStd"));
      float IMAGE_STD = (float)std;

      labelColors = (ArrayList)args.get("labelColors");
      outputType = args.get("outputType").toString();

      startTime = SystemClock.uptimeMillis();
      input = feedInputTensorImage(path, IMAGE_MEAN, IMAGE_STD);
      output = ByteBuffer.allocateDirect(tfLite.getOutputTensor(0).numBytes());
      output.order(ByteOrder.nativeOrder());
    }

    protected void runTflite() { tfLite.run(input, output); }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));

      if (input.limit() == 0) { result.error("Unexpected input position, bad file?", null, null); return; }
      if (output.position() != output.limit()) { result.error("Unexpected output position", null, null); return; }
      output.flip();

      result.success(fetchArgmax(output, labelColors, outputType));
    }
  }

  private class RunSegmentationOnBinary extends TfliteTask {
    List<Long> labelColors;
    String outputType;
    long startTime;
    ByteBuffer input, output;

    RunSegmentationOnBinary(HashMap args, Result result) throws IOException {
      super(args, result);

      byte[] binary = (byte[])args.get("binary");
      labelColors = (ArrayList)args.get("labelColors");
      outputType = args.get("outputType").toString();

      startTime = SystemClock.uptimeMillis();
      input = ByteBuffer.wrap(binary);
      output = ByteBuffer.allocateDirect(tfLite.getOutputTensor(0).numBytes());
      output.order(ByteOrder.nativeOrder());
    }

    protected void runTflite() { tfLite.run(input, output); }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));

      if (input.limit() == 0) { result.error("Unexpected input position, bad file?", null, null); return; }
      if (output.position() != output.limit()) { result.error("Unexpected output position", null, null); return; }
      output.flip();

      result.success(fetchArgmax(output, labelColors, outputType));
    }
  }

  private class RunSegmentationOnFrame extends TfliteTask {
    List<Long> labelColors;
    String outputType;
    long startTime;
    ByteBuffer input, output;

    RunSegmentationOnFrame(HashMap args, Result result) throws IOException {
      super(args, result);

      List<byte[]> bytesList= (ArrayList)args.get("bytesList");
      double mean = (double)(args.get("imageMean"));
      float IMAGE_MEAN = (float)mean;
      double std = (double)(args.get("imageStd"));
      float IMAGE_STD = (float)std;
      int imageHeight = (int)(args.get("imageHeight"));
      int imageWidth = (int)(args.get("imageWidth"));
      int rotation = (int)(args.get("rotation"));
      labelColors = (ArrayList)args.get("labelColors");
      outputType = args.get("outputType").toString();

      startTime = SystemClock.uptimeMillis();
      input = feedInputTensorFrame(bytesList, imageHeight, imageWidth, IMAGE_MEAN, IMAGE_STD, rotation);
      output = ByteBuffer.allocateDirect(tfLite.getOutputTensor(0).numBytes());
      output.order(ByteOrder.nativeOrder());
    }

    protected void runTflite() { tfLite.run(input, output); }

    protected void onRunTfliteDone() {
      Log.v("time", "Inference took " + (SystemClock.uptimeMillis() - startTime));

      if (input.limit() == 0) { result.error("Unexpected input position, bad file?", null, null); return; }
      if (output.position() != output.limit()) { result.error("Unexpected output position", null, null); return; }
      output.flip();

      result.success(fetchArgmax(output, labelColors, outputType));
    }
  }

  byte[] fetchArgmax(ByteBuffer output, List<Long> labelColors, String outputType) {
    Tensor outputTensor = tfLite.getOutputTensor(0);
    int outputBatchSize = outputTensor.shape()[0];
    assert outputBatchSize == 1;
    int outputHeight = outputTensor.shape()[1];
    int outputWidth = outputTensor.shape()[2];
    int outputChannels = outputTensor.shape()[3];

    Bitmap outputArgmax = null;
    byte[] outputBytes = new byte[outputWidth * outputHeight * 4];
    if (outputType.equals("png")) {
      outputArgmax = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
    }

    if (outputTensor.dataType() == DataType.FLOAT32) {
      for (int i = 0; i < outputHeight; ++i) {
        for (int j = 0; j < outputWidth; ++j) {
          int maxIndex = 0;
          float maxValue = 0.0f;
          for (int c = 0; c < outputChannels; ++c) {
            float outputValue = output.getFloat();
            if (outputValue > maxValue) {
              maxIndex = c;
              maxValue = outputValue;
            }
          }
          int labelColor = labelColors.get(maxIndex).intValue();
          if (outputType.equals("png")) {
            outputArgmax.setPixel(j, i, labelColor);
          } else {
            setPixel(outputBytes, i * outputWidth + j, labelColor);
          }
        }
      }
    } else {
      for (int i = 0; i < outputHeight; ++i) {
        for (int j = 0; j < outputWidth; ++j) {
          int maxIndex = 0;
          int maxValue = 0;
          for (int c = 0; c < outputChannels; ++c) {
            int outputValue = output.get();
            if (outputValue > maxValue) {
              maxIndex = c;
              maxValue = outputValue;
            }
          }
          int labelColor = labelColors.get(maxIndex).intValue();
          if (outputType.equals("png")) {
            outputArgmax.setPixel(j, i, labelColor);
          } else {
            setPixel(outputBytes, i * outputWidth + j, labelColor);
          }
        }
      }
    }
    if (outputType.equals("png")) {
      return compressPNG(outputArgmax);
    } else {
      return outputBytes;
    }
  }

  void setPixel(byte[] rgba, int index, long color) {
    rgba[index * 4] = (byte)((color >> 16) & 0xFF);
    rgba[index * 4 + 1] = (byte)((color >> 8) & 0xFF);
    rgba[index * 4 + 2] = (byte)(color & 0xFF);
    rgba[index * 4 + 3] = (byte)((color >> 24) & 0xFF);
  }

  byte[] compressPNG(Bitmap bitmap) {
    // https://stackoverflow.com/questions/4989182/converting-java-bitmap-to-byte-array#4989543
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
    byte[] byteArray = stream.toByteArray();
    // bitmap.recycle();
    return byteArray;
  }

  private float expit(final float x) {
    return (float) (1. / (1. + Math.exp(-x)));
  }

  private void softmax(final float[] vals) {
    float max = Float.NEGATIVE_INFINITY;
    for (final float val : vals) {
      max = Math.max(max, val);
    }
    float sum = 0.0f;
    for (int i = 0; i < vals.length; ++i) {
      vals[i] = (float) Math.exp(vals[i] - max);
      sum += vals[i];
    }
    for (int i = 0; i < vals.length; ++i) {
      vals[i] = vals[i] / sum;
    }
  }

  private static Matrix getTransformationMatrix(final int srcWidth,
                                                final int srcHeight,
                                                final int dstWidth,
                                                final int dstHeight,
                                                final boolean maintainAspectRatio)
  {
    final Matrix matrix = new Matrix();

    if (srcWidth != dstWidth || srcHeight != dstHeight) {
      final float scaleFactorX = dstWidth / (float) srcWidth;
      final float scaleFactorY = dstHeight / (float) srcHeight;

      if (maintainAspectRatio) {
        final float scaleFactor = Math.max(scaleFactorX, scaleFactorY);
        matrix.postScale(scaleFactor, scaleFactor);
      } else {
        matrix.postScale(scaleFactorX, scaleFactorY);
      }
    }

    matrix.invert(new Matrix());
    return matrix;
  }

  private void close() {
    if (tfLite != null)
      tfLite.close();
    labels = null;
    labelProb = null;
  }
}
