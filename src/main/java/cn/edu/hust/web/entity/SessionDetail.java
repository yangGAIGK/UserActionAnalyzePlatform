package cn.edu.hust.web.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("session_detail")
public class SessionDetail {

    @TableField("task_id")
    private Long taskId;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("page_id")
    private Long pageId;

    @TableField("action_time")
    private String actionTime;

    @TableField("search_keyword")
    private String searchKeyword;

    @TableField("click_category_id")
    private Long clickCategoryId;

    @TableField("click_product_id")
    private Long clickProductId;

    @TableField("order_category_ids")
    private String orderCategoryIds;

    @TableField("order_product_ids")
    private String orderProductIds;

    @TableField("pay_category_ids")
    private String payCategoryIds;

    @TableField("pay_product_ids")
    private String payProductIds;
}
