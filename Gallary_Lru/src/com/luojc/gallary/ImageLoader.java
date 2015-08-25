
package com.luojc.gallary;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.LruCache;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;

/**
 * 加载图片的工具类，Looper , Handler , Message的结合使用
 * 
 * @author luojc
 */
public class ImageLoader
{
    // 图片缓存类
    private LruCache<String, Bitmap> mLruCache;
    // 线程池
    private ExecutorService mThreadPool;
    // 线程池数量
    private int mThreadCount = 1;
    // 队列调度
    private Type mType = Type.LIFO;
    // runnable队列
    private LinkedList<Runnable> mTasks;
    // 用于启动线程池的线程
    private Thread mPoolThread;
    // 线程池对应的handler
    private Handler mPoolThreadHander;
    // 运行在UI线程的handler
    private Handler mHandler;
    // 引入一个值为1的信号量，防止mPoolThreadHander未初始化完成
    private volatile Semaphore mSemaphore = new Semaphore(1);
    private volatile Semaphore mPoolSemaphore;
    // 单例模式的模板变量
    private static ImageLoader mInstance;

    // 枚举两个调度方式
    public enum Type
    {
        FIFO, // first in first out
        LIFO // last in first out
    }

    /**
     * 单例实现，用于得到一个ImageLoader
     * 
     * @return
     */
    public static ImageLoader getInstance()
    {

        if (mInstance == null)
        {
            synchronized (ImageLoader.class)
            {
                if (mInstance == null)
                {
                    mInstance = new ImageLoader(1, Type.LIFO);
                }
            }
        }
        return mInstance;
    }

    /**
     * 注意修饰符是private 禁止直接用构造方法实例化，单例模式的特点
     * 
     * @param threadCount
     * @param type
     */
    private ImageLoader(int threadCount, Type type)
    {
        init(threadCount, type);
    }

    private void init(int threadCount, Type type)
    {
        // loop thread
        mPoolThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // 请求一个信号量
                    mSemaphore.acquire();
                } catch (InterruptedException e)
                {
                }
                // 在Thread里面使用handler要手动调用Looper.prepare/loop方法
                Looper.prepare();
                mPoolThreadHander = new Handler()
                {
                    @Override
                    public void handleMessage(Message msg)
                    { // 收到发来的消息，执行任务
                        mThreadPool.execute(getTask());
                        try
                        {
                            mPoolSemaphore.acquire();
                        } catch (InterruptedException e)
                        {
                        }
                    }
                };
                // 释放一个信号量
                mSemaphore.release();
                Looper.loop();
            }
        };
        mPoolThread.start();

        // 获取应用程序最大可用内存
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheSize = maxMemory / 8;
        mLruCache = new LruCache<String, Bitmap>(cacheSize)
        {
            @Override
            protected int sizeOf(String key, Bitmap value)
            {
                return value.getRowBytes() * value.getHeight();
            };
        };

        mThreadPool = Executors.newFixedThreadPool(threadCount);
        mPoolSemaphore = new Semaphore(threadCount);
        mTasks = new LinkedList<Runnable>();
        mType = type == null ? Type.LIFO : type;

    }

    /**
     * 通过异步消息加载图片 此处使用的是mHandler
     * 
     * @param path
     * @param imageView
     */
    public void loadImage(final String path, final ImageView imageView)
    {
        // 给这个imageView设置一个标志，值为图片路径
        imageView.setTag(path);
        // 注意此处是UI线程
        if (mHandler == null)
        {
            mHandler = new Handler()
            {
                @Override
                public void handleMessage(Message msg)
                {
                    ImgBeanHolder holder = (ImgBeanHolder) msg.obj;// 这里接受的是下面传来的holder对象
                    // 取出holder对象中的成员值
                    ImageView imageView = holder.imageView;
                    Bitmap bm = holder.bitmap;
                    String path = holder.path;
                    if (imageView.getTag().toString().equals(path))
                    {// 防止图片错位
                        imageView.setImageBitmap(bm);
                    }
                }
            };
        }
        // 从缓存中获取Bitmap，这个bitmap有可能不存在
        Bitmap bm = getBitmapFromLruCache(path);

        if (bm != null)// 如果存在，将这个bitmap，imageview，路径封装进holder发送消息
        {
            ImgBeanHolder holder = new ImgBeanHolder();
            holder.bitmap = bm;
            holder.imageView = imageView;
            holder.path = path;
            // Return a new Message instance from the global pool. Allows us to avoid allocating new
            // objects in many
            // cases.
            // 从整个Messge池中返回一个新的Message实例，在许多情况下使用它，因为它能避免分配新的对象
            Message message = Message.obtain();
            message.obj = holder;
            mHandler.sendMessage(message);
        } else// 不存在则添加一个任务，利用图片压缩方法获得新图片，存进缓存
        {
            addTask(new Runnable()
            {
                @Override
                public void run()
                {

                    ImageSize imageSize = getImageViewWidth(imageView);

                    int reqWidth = imageSize.width;
                    int reqHeight = imageSize.height;

                    Bitmap bm = decodeSampledBitmapFromResource(path, reqWidth,
                            reqHeight);
                    addBitmapToLruCache(path, bm);
                    ImgBeanHolder holder = new ImgBeanHolder();
                    holder.bitmap = getBitmapFromLruCache(path);
                    holder.imageView = imageView;
                    holder.path = path;
                    Message message = Message.obtain();
                    message.obj = holder;// 此处将holder作为消息传给上面handler
                    mHandler.sendMessage(message);
                    mPoolSemaphore.release();
                }
            });
        }

    }

    /**
     * 添加一个任务
     * 
     * @param runnable
     */
    private synchronized void addTask(Runnable runnable)
    {
        try
        {
            // 请求信号量，防止mPoolThreadHander为null
            if (mPoolThreadHander == null)
                mSemaphore.acquire();
        } catch (InterruptedException e)
        {
        }
        mTasks.add(runnable);
        mPoolThreadHander.sendEmptyMessage(0x110);// 发送消息给线程池，让线程池执行任务，不一定是上一行添加的
    }

    /**
     * 取出任务，按照要求的方式取
     * 
     * @return
     */
    private synchronized Runnable getTask()
    {
        if (mType == Type.FIFO)
        {
            // 如果任务队列先进先出，那么取第一个
            return mTasks.removeFirst();
        } else if (mType == Type.LIFO)
        { // 如果任务队列后进先出，那么取最后一个
            return mTasks.removeLast();
        }
        return null;
    }

    /**
     * 单例获得该实例对象
     * 
     * @return
     */
    public static ImageLoader getInstance(int threadCount, Type type)
    {

        if (mInstance == null)
        {
            synchronized (ImageLoader.class)
            {
                if (mInstance == null)
                {
                    mInstance = new ImageLoader(threadCount, type);
                }
            }
        }
        return mInstance;
    }

    // ----------下面是图片压缩的方法----------

    /**
     * 根据ImageView获得适当的压缩的宽和高
     * 
     * @param imageView
     * @return
     */
    private ImageSize getImageViewWidth(ImageView imageView)
    {
        ImageSize imageSize = new ImageSize();
        final DisplayMetrics displayMetrics = imageView.getContext()
                .getResources().getDisplayMetrics();
        final LayoutParams params = imageView.getLayoutParams();

        int width = params.width == LayoutParams.WRAP_CONTENT ? 0 : imageView
                .getWidth(); // Get actual image width
        if (width <= 0)
            width = params.width; // Get layout width parameter
        if (width <= 0)
            width = getImageViewFieldValue(imageView, "mMaxWidth"); // Check
                                                                    // maxWidth
                                                                    // parameter
        if (width <= 0)
            width = displayMetrics.widthPixels;
        int height = params.height == LayoutParams.WRAP_CONTENT ? 0 : imageView
                .getHeight(); // Get actual image height
        if (height <= 0)
            height = params.height; // Get layout height parameter
        if (height <= 0)
            height = getImageViewFieldValue(imageView, "mMaxHeight"); // Check
                                                                      // maxHeight
                                                                      // parameter
        if (height <= 0)
            height = displayMetrics.heightPixels;
        imageSize.width = width;
        imageSize.height = height;
        return imageSize;

    }

    /**
     * 从缓存中获取一张图片，如果不存在就返回null。
     */
    private Bitmap getBitmapFromLruCache(String key)
    {
        return mLruCache.get(key);
    }

    /**
     * 往缓存中添加一张图片
     * 
     * @param key
     * @param bitmap
     */
    private void addBitmapToLruCache(String key, Bitmap bitmap)
    {
        if (getBitmapFromLruCache(key) == null)
        {
            if (bitmap != null)
                mLruCache.put(key, bitmap);
        }
    }

    /**
     * 压缩图片
     * 
     * @param options
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private int calculateInSampleSize(BitmapFactory.Options options,
            int reqWidth, int reqHeight)
    {
        // 源图片的宽度
        int width = options.outWidth;
        int height = options.outHeight;
        int inSampleSize = 1;

        if (width > reqWidth && height > reqHeight)
        {
            // 计算出实际宽度和目标宽度的比率
            int widthRatio = Math.round((float) width / (float) reqWidth);
            int heightRatio = Math.round((float) width / (float) reqWidth);
            inSampleSize = Math.max(widthRatio, heightRatio);
        }
        return inSampleSize;
    }

    /**
     * 得到压缩后图片
     * 
     * @param pathName
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap decodeSampledBitmapFromResource(String pathName,
            int reqWidth, int reqHeight)
    {
        // 第一次解析将inJustDecodeBounds设置为true，来获取图片大小
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(pathName, options);
        // 调用上面定义的方法计算inSampleSize值
        options.inSampleSize = calculateInSampleSize(options, reqWidth,
                reqHeight);
        // 使用获取到的inSampleSize值再次解析图片
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(pathName, options);

        return bitmap;
    }

    /**
     * 单个图片的类，包括bitmap，imageView，path
     * 
     * @author luojc
     */
    private class ImgBeanHolder
    {
        Bitmap bitmap;
        ImageView imageView;
        String path;
    }

    /**
     * 图片大小类
     * 
     * @author luojc
     */
    private class ImageSize
    {
        int width;
        int height;
    }

    /**
     * 反射获得ImageView设置的最大宽度和高度
     * 
     * @param object
     * @param fieldName
     * @return
     */
    private static int getImageViewFieldValue(Object object, String fieldName)
    {
        int value = 0;
        try
        {
            Field field = ImageView.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            int fieldValue = (Integer) field.get(object);
            if (fieldValue > 0 && fieldValue < Integer.MAX_VALUE)
            {
                value = fieldValue;

                Log.e("TAG", value + "");
            }
        } catch (Exception e)
        {
        }
        return value;
    }

    // ----------上面是图片压缩的方法----------

    // getter/setter
    public int getmThreadCount() {
        return mThreadCount;
    }

    public void setmThreadCount(int mThreadCount) {
        this.mThreadCount = mThreadCount;
    }

}
