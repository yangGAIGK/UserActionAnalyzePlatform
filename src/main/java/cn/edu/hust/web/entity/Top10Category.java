package cn.edu.hust.web.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("top10_category")
public class Top10Category {
    @TableField("task_id")
    private Long taskId;
    @TableField("category_id")
    private Long categoryId;
    @TableField("click_count")
    private Long clickCount;
    @TableField("order_count")
    private Long orderCount;
    @TableField("pay_count")
    private Long payCount;
}
