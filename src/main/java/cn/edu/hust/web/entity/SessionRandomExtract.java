package cn.edu.hust.web.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("session_random_extract")
public class SessionRandomExtract {
    
    @TableField("task_id")
    private Long taskId;

    @TableField("session_id")
    private String sessionId;

    @TableField("start_time")
    private String startTime;

    @TableField("search_keywords")
    private String searchKeywords;

    @TableField("click_category_ids")
    private String clickCategoryIds;
}
