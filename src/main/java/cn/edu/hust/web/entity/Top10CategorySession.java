package cn.edu.hust.web.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("top10_category_session")
public class Top10CategorySession {
    
    @TableField("task_id")
    private Long taskId;

    @TableField("category_id")
    private Long categoryId;

    @TableField("session_id")
    private String sessionId;

    @TableField("click_count")
    private Long clickCount;
}
