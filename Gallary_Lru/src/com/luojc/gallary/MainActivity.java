
package com.luojc.gallary;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;

import com.luojc.gallary.ImageLoader.Type;

import android.support.v7.app.ActionBarActivity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * MainActivity
 * 
 * @author luojc
 */
public class MainActivity extends ActionBarActivity {
    private ArrayList<String> pathsrcs;
    private String folder;
    private GridView mGridView;
    private ImageAdapter mImageAdapter;
    private File[] files;
    private final static int THREAD_COUNT = 3;
    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            mImageAdapter = new ImageAdapter(getApplicationContext());
            mGridView.setAdapter(mImageAdapter);
        }

    };

    private void getPaths() {
        if (!Environment.getExternalStorageState().equals(
                Environment.MEDIA_MOUNTED))
        {
            Toast.makeText(this, "暂无外部存储", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() {

            @Override
            public void run() {
                folder = "/sdcard/DCIM/Camera";
                files = new File(folder).listFiles();
                pathsrcs = new ArrayList<String>();
                for (File file : files) {
                    String path = file.getPath();
                    if (validate(path)) {
                        pathsrcs.add(path);
                    }
                }
                mHandler.sendEmptyMessage(0x110);

            }

        }).start();

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mGridView = (GridView) findViewById(R.id.id_gridView);
        getPaths();
    }

    /**
     * 验证是否为图片路径的方法
     * 
     * @param fileName
     * @return
     */
    private boolean validate(String fileName) {
        int idx = fileName.indexOf(".");
        String subfix = fileName.substring(idx + 1);
        if (fileName.equals("")) {
            return false;
        }
        if ("jpg".equals(subfix) || "png".equals(subfix)) {
            return true;
        } else {
            return false;
        }

    }

    /*
     * ------------------------------------适配器-------------------------------------------------
     * 自定义的ImageAdapter类
     */
    class ImageAdapter extends BaseAdapter {
        private Context mContext;

        private final class ViewHolder {
            ImageView mImageView;
        }

        private LayoutInflater mInflater;// 用于设置布局的类

        public ImageAdapter(Context c) {
            mContext = c;
            mInflater = LayoutInflater.from(mContext);
        }

        @Override
        public int getCount() {
            return pathsrcs.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        /**
         * 重写getView方法
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder = null;
            if (convertView == null) { // if it's not recycled, initialize some // attributes
                holder = new ViewHolder();
                convertView = mInflater.inflate(R.layout.grid_item, parent, false);
                holder.mImageView = (ImageView) convertView.findViewById(R.id.id_item_image);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            String path = pathsrcs.get(position);
            ImageLoader.getInstance(THREAD_COUNT, Type.FIFO).loadImage(path, holder.mImageView);
            return convertView;
        }

    }

}
