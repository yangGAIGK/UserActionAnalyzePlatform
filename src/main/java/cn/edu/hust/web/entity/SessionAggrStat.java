package cn.edu.hust.web.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("session_aggr_stat")
public class SessionAggrStat {
    
    @TableId("task_id")
    private Long taskId;

    @TableField("session_count")
    private Long sessionCount;

    @TableField("`1s_3s`")
    private Double visitLength_1s_3s_ratio;

    @TableField("`4s_6s`")
    private Double visitLength_4s_6s_ratio;

    @TableField("`7s_9s`")
    private Double visitLength_7s_9s_ratio;

    @TableField("`10s_30s`")
    private Double visitLength_10s_30s_ratio;

    @TableField("`30s_60s`")
    private Double visitLength_30s_60s_ratio;

    @TableField("`1m_3m`")
    private Double visitLength_1m_3m_ratio;

    @TableField("`3m_10m`")
    private Double visitLength_3m_10m_ratio;

    @TableField("`10m_30m`")
    private Double visitLength_10m_30m_ratio;

    @TableField("`30m`")
    private Double visitLength_30m_ratio;

    @TableField("`1_3`")
    private Double stepLength_1_3_ratio;

    @TableField("`4_6`")
    private Double stepLength_4_6_ratio;

    @TableField("`7_9`")
    private Double stepLength_7_9_ratio;

    @TableField("`10_30`")
    private Double stepLength_10_30_ratio;

    @TableField("`30_60`")
    private Double stepLength_30_60_ratio;

    @TableField("`60`")
    private Double stepLength_60_ratio;
}
