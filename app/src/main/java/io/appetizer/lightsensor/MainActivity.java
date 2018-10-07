package io.appetizer.lightsensor;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.media.MediaPlayer;
import android.content.res.AssetFileDescriptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;

import android.Manifest;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;
import android.media.AudioManager;


import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.chart.PointStyle;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;


public class MainActivity extends Activity {
    public SensorManager sensorManager;
    private AudioManager mAudioManager;
    public Sensor accelSensor;
    TextView lightIntensity, troughNum;
    SensorEventListener lightSensorListener;
    Handler avgHandler;
    Thread avgThread;
    //图表相关
    private XYSeries series;
    private XYMultipleSeriesDataset mDataset;
    private GraphicalView chart;
    private XYMultipleSeriesRenderer renderer;
    public int INTERVAL = 10;
    public int xMax = 500;
    public int yMax = 150;
    public int index = 0;//指示这段时间一共写入了多少个数据
    double ambientLight = 0;
    int ambientLightIndex = 0;
    //在这里可以设置缓冲区的长度，用于求平均数
    double[] buffer = new double[100];
    Map<Integer, Double> dataset = new HashMap<Integer, Double>();
    Map<Integer, Double> trough = new HashMap<Integer, Double>();
    Map<Integer, Double> realtrough = new HashMap<Integer, Double>();
    public double AVERAGE = 0;//存储平均值
    private int addX = -1;
    double pre = 0;
    int potentialkey = 0;
    double potentialvalue = 0.0;
    private MediaPlayer mediaPlayer = new MediaPlayer();
    int lastindex = 0;
    boolean start = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initListeners();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        initViews();
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        sensorManager.registerListener(lightSensorListener, accelSensor, sensorManager.SENSOR_DELAY_UI);
        //初始化图表
        initChart("Times(测量次数)", "单位", 0, xMax, 0, yMax);
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        } else {
            initMediaPlayer();//初始化播放器 MediaPlayer
        }
        avgHandler = new AvgHandler();
        avgThread = new Thread(runnable);//定期更新平均值的线程启动
        avgThread.start();
    }

    public void initViews() {
        lightIntensity = (TextView) findViewById(R.id.light_intensity);
        troughNum = (TextView) findViewById(R.id.troughNum);
    }

    private void initMediaPlayer() {
        try {
            AssetFileDescriptor fd = getAssets().openFd("music.mp3");
            mediaPlayer.setDataSource(fd.getFileDescriptor());
            mediaPlayer.setLooping(true);//设置为循环播放
            mediaPlayer.prepare();//初始化播放器MediaPlayer

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case 1:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initMediaPlayer();
                } else {
                    Toast.makeText(this, "拒绝权限，将无法使用程序。", Toast.LENGTH_LONG).show();
                    //finish();
                }
                break;
            default:
        }
    }


    @Override
    protected void onPause() {
        // TODO Auto-generated method stub
        super.onPause();
        if (avgThread != null)
            avgThread.interrupt();
    }

    @Override
    protected void onDestroy() {
        // TODO Auto-generated method stub
        super.onDestroy();
        if (lightSensorListener != null) {
            sensorManager.unregisterListener(lightSensorListener);
        }

        if (avgThread != null)
            avgThread.interrupt();

        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
        }
    }


    /**
     * 初始化各类监听器
     */
    private void initListeners() {
        lightSensorListener = new SensorEventListener() {//只有一个返回参数的监听器
            @Override
            public void onSensorChanged(SensorEvent event) {//如果传感器动作发生变化就会调用此方法
                // TODO Auto-generated method stub
                //lightIntensity.setText(event.values[0] + "");
                giveAverage(event.values[0]);//将当前测量的结果写入buffer，然后定期求buffer里面的平均值，并显示
                if(!start) start = true;
            }
            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // TODO Auto-generated method stub
            }
        };
    }

    /**
     * 初始化图表
     */
    private void initChart(String xTitle, String yTitle, int minX, int maxX, int minY, int maxY) {
        //这里获得main界面上的布局，下面会把图表画在这个布局里面
        LinearLayout layout = (LinearLayout) findViewById(R.id.chart);
        //这个类用来放置曲线上的所有点，是一个点的集合，根据这些点画出曲线
        series = new XYSeries("历史曲线");
        //创建一个数据集的实例，这个数据集将被用来创建图表
        mDataset = new XYMultipleSeriesDataset();
        //将点集添加到这个数据集中
        mDataset.addSeries(series);

        //以下都是曲线的样式和属性等等的设置，renderer相当于一个用来给图表做渲染的句柄
        int lineColor = Color.WHITE;
        PointStyle style = PointStyle.CIRCLE;
        renderer = buildRenderer(lineColor, style, true);

        //设置好图表的样式
        setChartSettings(renderer, xTitle, yTitle,
                minX, maxX, //x轴最小最大值
                minY, maxY, //y轴最小最大值
                Color.BLACK, //坐标轴颜色
                Color.WHITE//标签颜色
        );

        //生成图表
        chart = ChartFactory.getLineChartView(this, mDataset, renderer);

        //将图表添加到布局中去
        layout.addView(chart, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT));
    }

    /**
     * 一个子线程，没隔固定时间计算这段时间的平均值，并给textView赋值
     */
    Runnable runnable = new Runnable() {
        @Override
        public void run() {
            // TODO Auto-generated method stub
            System.out.println("线程已经启动");
            while (true) {
                try {
                    Thread.sleep(INTERVAL);//没隔固定时间求平均数
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                    avgThread = new Thread(runnable);
                    avgThread.start();
                }
                if (index != 0) {
                    double sum = 0;
                    for (int i = 0; i < index; i++) {
                        sum += buffer[i];
                    }
                    AVERAGE = sum / new Double(index);
                    index = 0;//让下标恢复
                }
                avgHandler.sendEmptyMessage(1);
                //高精度除法，还能四舍五入
//			AVERAGE = MathTools.div(sum, buffer.length, 4);
            }
        }
    };

    /**
     * 更新平均值的显示值
     */
    public void setAverageView() {
        if (lightIntensity == null) return;
        lightIntensity.setText(AVERAGE + "");
    }

    /**
     * 每隔固定时间给平均值赋值，并且更新图表的操作
     *
     * @author love fang
     */
    class AvgHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            // TODO Auto-generated method stub
            setAverageView();//显示平均值
            if(start) updateChart();//更新图表，非常重要的方法
            //把当前值存入数据库
        }
    }

    /**
     * 接受当前传感器的测量值，存到缓存区中去，并将下标加一
     *
     * @param data
     */
    public void giveAverage(double data) {
        buffer[index] = data;
        index++;
    }


    protected XYMultipleSeriesRenderer buildRenderer(int color, PointStyle style, boolean fill) {
        XYMultipleSeriesRenderer renderer = new XYMultipleSeriesRenderer();

        //设置图表中曲线本身的样式，包括颜色、点的大小以及线的粗细等
        XYSeriesRenderer r = new XYSeriesRenderer();
        r.setColor(color);
        r.setPointStyle(style);
        r.setFillPoints(fill);
        r.setLineWidth(2);//这是线宽
        renderer.addSeriesRenderer(r);
        return renderer;
    }

    protected void setChartSettings(XYMultipleSeriesRenderer renderer, String xTitle, String yTitle,
                                    double xMin, double xMax, double yMin, double yMax, int axesColor, int labelsColor) {
        //有关对图表的渲染可参看api文档
        renderer.setChartTitle("LightSensor");//设置标题
        renderer.setChartTitleTextSize(20);
        renderer.setXAxisMin(xMin);//设置x轴的起始点
        renderer.setXAxisMax(xMax);//设置一屏有多少个点
        renderer.setYAxisMin(yMin);
        renderer.setYAxisMax(yMax);
        renderer.setBackgroundColor(Color.BLACK);
        renderer.setLabelsColor(Color.YELLOW);
        renderer.setAxesColor(axesColor);
        renderer.setLabelsColor(labelsColor);
        renderer.setShowGrid(true);
        renderer.setGridColor(Color.BLUE);//设置格子的颜色
        renderer.setXLabels(20);//没有什么卵用
        renderer.setYLabels(20);//把y轴刻度平均分成多少个
        renderer.setLabelsTextSize(25);
        renderer.setXTitle(xTitle);//x轴的标题
        renderer.setYTitle(yTitle);//y轴的标题
        renderer.setAxisTitleTextSize(30);
        renderer.setYLabelsAlign(Paint.Align.RIGHT);
        renderer.setPointSize((float) 2);
        renderer.setShowLegend(false);//说明文字
        renderer.setLegendTextSize(20);
    }

    private void updateChart() {
        addX++;
        mDataset.removeSeries(series);
        series.add(addX, AVERAGE);//最重要的一句话，以xy对的方式往里放值
        if (AVERAGE > ambientLight) {
            ambientLight = AVERAGE;
            ambientLightIndex = addX;
        }
        if ((addX - ambientLightIndex) > 500) {
            ambientLight = AVERAGE;
            ambientLightIndex = addX;
        }
        dataset.put(addX, AVERAGE);
        if (AVERAGE < pre) {
            potentialkey = addX;
            potentialvalue = AVERAGE;
        }
        if (AVERAGE > pre && pre == potentialvalue && pre < (0.7 * ambientLight) && pre != 0) {
            trough.put(potentialkey, potentialvalue);
            trough.put(addX - 1, pre);
            realtrough.put(potentialkey, potentialvalue);
            realtrough.put(addX - 1, pre);
            lastindex = addX - 1;
        }
        if (addX > xMax - 1) {
            renderer.setXAxisMin(addX - xMax);
            renderer.setXAxisMax(addX);
            dataset.remove(addX - xMax);
        }
        mDataset.addSeries(series);
        chart.invalidate();
        pre = AVERAGE;
        if (troughNum != null) troughNum.setText(countTrough(addX) + "");
        gestureControl(addX);
    }

    private int countTrough(int index) {
        int pre = index - 500;
        int num = 0;
        Iterator<Map.Entry<Integer, Double>> iterator = trough.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Double> entry = iterator.next();
            int key = entry.getKey();
            if (key > pre) num++;
        }
        return num / 2;
    }


    private void gestureControl(int index) {
        if ((index - lastindex) < 150) return;
        int num = realtrough.size() / 2;
        System.out.println("realtime " + num);
        switch (num) {
            case 1:
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
                realtrough.clear();
                break;
            case 2:
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                realtrough.clear();
                break;
            case 3:
                mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                realtrough.clear();
                break;
            case 4:
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
                realtrough.clear();
                break;
            default:
                break;
        }
    }


}
