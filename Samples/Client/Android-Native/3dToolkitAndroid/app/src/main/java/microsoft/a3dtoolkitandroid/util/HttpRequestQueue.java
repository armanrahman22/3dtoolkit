package microsoft.a3dtoolkitandroid.util;

import android.content.Context;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;
import com.facebook.stetho.okhttp3.StethoInterceptor;

import java.util.ArrayList;

import okhttp3.Interceptor;

/**
 * Created by arrahm on 10/11/2017.
 */

public class HttpRequestQueue {
    private static HttpRequestQueue httpRequestQueue;
    private RequestQueue volleyRequestQueue;

    private HttpRequestQueue(Context context) {
        if (volleyRequestQueue == null) {
            ArrayList<Interceptor> interceptors = new ArrayList<>();
            interceptors.add(new StethoInterceptor());
            volleyRequestQueue = Volley.newRequestQueue
                    (context, new OkHttpStack(interceptors));
        }
    }

    public static HttpRequestQueue getInstance(Context context) {
        if (httpRequestQueue == null) {
            synchronized (HttpRequestQueue.class) {
                if (httpRequestQueue == null) {
                    httpRequestQueue = new HttpRequestQueue(context);
                }
            }
        }
        return httpRequestQueue;
    }

    /**
     * set a mock request queue for testing purposes
     * @param mockRequestQueue: should be a mock RequestQueue object generated by mockito
     */
    public static void setInstance(HttpRequestQueue mockRequestQueue) {
        httpRequestQueue = mockRequestQueue;
    }

    public void addToQueue(Request request, String tag) {
        request.setTag(tag);
        volleyRequestQueue.add(request);
    }

    public void cancelAll(String tag) {
        volleyRequestQueue.cancelAll(tag);
    }
}
