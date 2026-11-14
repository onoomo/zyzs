package xyz.doikki.videoplayer.player;

import android.content.Context;
import android.util.AttributeSet;



/**
 * 可播放在线和本地url
 * Created by Doikki on 2022/7/18.
 */
public class VideoView extends BaseVideoView<AbstractPlayer> {
    public VideoView(Context context) {
        super(context);
    }

    public VideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
