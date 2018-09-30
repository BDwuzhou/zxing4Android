package com.google.zxing.client.android.decode;

import android.graphics.Rect;
import android.hardware.Camera;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.ResultPoint;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.android.scan.CaptureActivity;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.DecoderResult;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.qrcode.decoder.Decoder;
import com.google.zxing.qrcode.decoder.QRCodeDecoderMetaData;
import com.google.zxing.qrcode.detector.Detector;

import java.util.List;
import java.util.Map;

/**
 * QR解码器，扫描过程中可以根据二维码大小自动缩放镜头
 *
 * @see com.google.zxing.qrcode.QRCodeReader
 */
public class AutoZoomQRReader implements Reader {
    private static final ResultPoint[] NO_POINTS = new ResultPoint[0];
    private final Decoder decoder = new Decoder();
    private final CaptureActivity activity;

    AutoZoomQRReader(CaptureActivity activity) {
        this.activity = activity;
    }

    @Override
    public Result decode(BinaryBitmap binaryBitmap) throws NotFoundException, ChecksumException, FormatException {
        return decode(binaryBitmap, null);
    }

    @Override
    public Result decode(BinaryBitmap binaryBitmap, Map<DecodeHintType, ?> map) throws NotFoundException, ChecksumException, FormatException {
        DecoderResult decoderResult;
        ResultPoint[] points;
        if (map != null && map.containsKey(DecodeHintType.PURE_BARCODE)) {
            BitMatrix bits = extractPureBits(binaryBitmap.getBlackMatrix());
            decoderResult = decoder.decode(bits, map);
            points = NO_POINTS;
        } else {
            //1、将图像进行二值化处理，1、0代表黑、白。( 二维码的使用getBlackMatrix方法 )
            DetectorResult detectorResult = new Detector(binaryBitmap.getBlackMatrix()).detect(map);
            //2、寻找定位符、校正符，然后将原图像中符号码部分取出。（detector代码实现的功能）
            if (tryAutoZoom(detectorResult)) {
                return null;//缩放一次，结果作废
            }
            //3、对符号码矩阵按照编码规范进行解码，得到实际信息（decoder代码实现的功能）
            decoderResult = decoder.decode(detectorResult.getBits(), map);
            points = detectorResult.getPoints();
        }
        // 如果二维码是镜像的:交换左下角和右上角的点。
        if (decoderResult.getOther() instanceof QRCodeDecoderMetaData) {
            ((QRCodeDecoderMetaData) decoderResult.getOther()).applyMirroredCorrection(points);
        }
        Result result = new Result(decoderResult.getText(), decoderResult.getRawBytes(), points, BarcodeFormat.QR_CODE);
        List<byte[]> byteSegments = decoderResult.getByteSegments();
        if (byteSegments != null) {
            result.putMetadata(ResultMetadataType.BYTE_SEGMENTS, byteSegments);
        }
        String ecLevel = decoderResult.getECLevel();
        if (ecLevel != null) {
            result.putMetadata(ResultMetadataType.ERROR_CORRECTION_LEVEL, ecLevel);
        }
        if (decoderResult.hasStructuredAppend()) {
            result.putMetadata(ResultMetadataType.STRUCTURED_APPEND_SEQUENCE,
                    decoderResult.getStructuredAppendSequenceNumber());
            result.putMetadata(ResultMetadataType.STRUCTURED_APPEND_PARITY,
                    decoderResult.getStructuredAppendParity());
        }
        return result;
    }

    @Override
    public void reset() {
        //do nothing
    }

    /**
     * @see com.google.zxing.qrcode.QRCodeReader#extractPureBits(BitMatrix)
     */
    private static BitMatrix extractPureBits(BitMatrix image) throws NotFoundException {
        int[] leftTopBlack = image.getTopLeftOnBit();
        int[] rightBottomBlack = image.getBottomRightOnBit();
        if (leftTopBlack != null && rightBottomBlack != null) {
            float moduleSize = moduleSize(leftTopBlack, image);
            int top = leftTopBlack[1];
            int bottom = rightBottomBlack[1];
            int left = leftTopBlack[0];
            int right = rightBottomBlack[0];
            if (left < right && top < bottom) {
                if (bottom - top != right - left && (right = left + (bottom - top)) >= image.getWidth()) {
                    throw NotFoundException.getNotFoundInstance();
                } else {
                    int matrixWidth = Math.round((float) (right - left + 1) / moduleSize);
                    int matrixHeight = Math.round((float) (bottom - top + 1) / moduleSize);
                    if (matrixWidth > 0 && matrixHeight > 0) {
                        if (matrixHeight != matrixWidth) {
                            throw NotFoundException.getNotFoundInstance();
                        } else {
                            int nudge = (int) (moduleSize / 2.0F);
                            top += nudge;
                            int nudgedTooFarRight;
                            if ((nudgedTooFarRight = (left += nudge) + (int) ((float) (matrixWidth - 1) * moduleSize) - right) > 0) {
                                if (nudgedTooFarRight > nudge) {
                                    throw NotFoundException.getNotFoundInstance();
                                }
                                left -= nudgedTooFarRight;
                            }
                            int nudgedTooFarDown;
                            if ((nudgedTooFarDown = top + (int) ((float) (matrixHeight - 1) * moduleSize) - bottom) > 0) {
                                if (nudgedTooFarDown > nudge) {
                                    throw NotFoundException.getNotFoundInstance();
                                }
                                top -= nudgedTooFarDown;
                            }
                            BitMatrix bits = new BitMatrix(matrixWidth, matrixHeight);
                            for (int y = 0; y < matrixHeight; ++y) {
                                int iOffset = top + (int) ((float) y * moduleSize);
                                for (int x = 0; x < matrixWidth; ++x) {
                                    if (image.get(left + (int) ((float) x * moduleSize), iOffset)) {
                                        bits.set(x, y);
                                    }
                                }
                            }
                            return bits;
                        }
                    } else {
                        throw NotFoundException.getNotFoundInstance();
                    }
                }
            } else {
                throw NotFoundException.getNotFoundInstance();
            }
        } else {
            throw NotFoundException.getNotFoundInstance();
        }
    }

    /**
     * @param detectorResult 解析到的原图像
     * @return true-进行了一次缩放，本次扫描作废  false-不需要缩放，本次扫描结果可以解析
     */
    private boolean tryAutoZoom(DetectorResult detectorResult) {
        if (activity != null) {
            CameraManager cameraManager = activity.getCameraManager();
            ResultPoint[] p = detectorResult.getPoints();
            //定位二维码最少需要两个点，计算二维码的宽度，两点间距离公式
            float point1X = p[0].getX();
            float point1Y = p[0].getY();
            float point2X = p[1].getX();
            float point2Y = p[1].getY();
            int len = (int) Math.sqrt(Math.abs(point1X - point2X) * Math.abs(point1X - point2X) +
                    Math.abs(point1Y - point2Y) * Math.abs(point1Y - point2Y));
            Rect frameRect = cameraManager.getFramingRect();//取景框
            if (frameRect != null) {
                int frameWidth = frameRect.right - frameRect.left;//取景框宽度
                Camera camera = cameraManager.getOpenCamera().getCamera();
                Camera.Parameters parameters = camera.getParameters();
                int maxZoom = parameters.getMaxZoom();
                int zoom = parameters.getZoom();
                if (parameters.isZoomSupported()) {
                    if (len <= frameWidth / 4) {//二维码宽度小于扫描框的1/4，放大镜头
                        if (zoom == 0) {
                            zoom = maxZoom / 2;
                        } else if (zoom <= maxZoom - 10) {
                            zoom += 10;
                        } else {
                            zoom = maxZoom;
                        }
                        parameters.setZoom(zoom);
                        camera.setParameters(parameters);
                        return true;
                    } else if (len > cameraManager.getConfigManager().getScreenResolution().x) {
                        //二维码宽度大于屏幕宽度，需要缩小镜头，这种情况基本不会发生
                        if (zoom >= maxZoom) {
                            zoom = maxZoom / 2;
                        } else if (zoom >= 10) {
                            zoom -= 10;
                        } else {
                            zoom = 0;
                        }
                        parameters.setZoom(zoom);
                        camera.setParameters(parameters);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @see com.google.zxing.qrcode.QRCodeReader#moduleSize(int[], BitMatrix)
     */
    private static float moduleSize(int[] leftTopBlack, BitMatrix image) throws NotFoundException {
        int height = image.getHeight();
        int width = image.getWidth();
        int x = leftTopBlack[0];
        int y = leftTopBlack[1];
        boolean inBlack = true;
        for (int transitions = 0; x < width && y < height; ++y) {
            if (inBlack != image.get(x, y)) {
                ++transitions;
                if (transitions == 5) {
                    break;
                }
                inBlack = !inBlack;
            }
            ++x;
        }
        if (x != width && y != height) {
            return (float) (x - leftTopBlack[0]) / 7.0F;
        } else {
            throw NotFoundException.getNotFoundInstance();
        }
    }
}
