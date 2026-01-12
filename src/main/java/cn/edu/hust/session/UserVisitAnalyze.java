package cn.edu.hust.session;

import cn.edu.hust.conf.ConfigurationManager;
import cn.edu.hust.constant.Constants;
import cn.edu.hust.dao.TaskDao;
import cn.edu.hust.dao.factory.DaoFactory;
import cn.edu.hust.domain.*;
import cn.edu.hust.mockData.MockData;
import cn.edu.hust.util.*;
import com.alibaba.fastjson.JSONObject;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.hive.HiveContext;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

import java.util.*;

/**
 * 用户可以查询的范围包含
 * 1。用户的职业
 * 2。用户的性别
 * 3。用户城市
 * 4。用户年龄
 * 5。获取搜索词
 * 6。获取点击品类
 */
public class UserVisitAnalyze {
    public static void main(String[] args)
    {
        // // args=new String[]{"1"};
        // /**
        //  * 构建spark上下文
        //  */
        SparkConf conf=new SparkConf().setAppName(Constants.APP_NAME_SESSION);
        JavaSparkContext context=new JavaSparkContext(conf);
        SQLContext sc=getSQLContext(context.sc());
        if(!(ConfigurationManager.getBoolean(Constants.SPARK_LOCAL))) {
            sc.sql("USE BigDataPlatm");
        }
        //生成模拟数据
        mock(context,sc);

        //拿到相应的Dao组建
        TaskDao dao= DaoFactory.getTaskDao();
        //从外部传入的参数获取任务的id
        Long taskId=ParamUtils.getTaskIdFromArgs(args);
        //从数据库中查询出相应的task
        Task task=dao.findTaskById(taskId);
        JSONObject jsonObject=JSONObject.parseObject(task.getTaskParam());

        //获取指定范围内的Sesssion
        JavaRDD<Row> sessionRangeDate=getActionRDD(sc,jsonObject);

        //这里增加一个新的方法，主要是映射
        JavaPairRDD<String,Row> sessionInfoPairRDD=getSessonInfoPairRDD(sessionRangeDate);
        //重复用到的RDD进行持久化
        sessionInfoPairRDD.persist(StorageLevel.DISK_ONLY());
        //上面的两个RDD是
        //按照Sesson进行聚合
        JavaPairRDD<String,String> sesssionAggregateInfoRDD=aggregateBySessionId(sc,sessionInfoPairRDD);

        //通过条件对RDD进行筛选
        // 重构，同时统计
        Accumulator<String> sessionAggrStatAccumulator=context.accumulator("",new SessionAggrStatAccumulator());

        //在进行accumulator之前，需要aciton动作，不然会为空
        JavaPairRDD<String,String> filteredSessionRDD=filterSessionAndAggrStat(sesssionAggregateInfoRDD,jsonObject,sessionAggrStatAccumulator);
        //重复用到的RDD进行持久化
        filteredSessionRDD.persist(StorageLevel.DISK_ONLY());
        //获取符合过滤条件的全信息公共RDD
        JavaPairRDD<String, Row> commonFullClickInfoRDD=getFilterFullInfoRDD(filteredSessionRDD,sessionInfoPairRDD);

        //重复用到的RDD进行持久化
        commonFullClickInfoRDD.persist(StorageLevel.DISK_ONLY());
        //session聚合统计，统计出访问时长和访问步长的各个区间所占的比例
        /**
         * 重构实现的思路：
         * 1。不要去生成任何的新RDD
         * 2。不要去单独遍历一遍sesion的数据
         * 3。可以在聚合数据的时候可以直接计算session的访问时长和访问步长
         * 4。在以前的聚合操作中，可以在以前的基础上进行计算加上自己实现的Accumulator来进行一次性解决
         * 开发Spark的经验准则
         * 1。尽量少生成RDD
         * 2。尽量少对RDD进行蒜子操作，如果可能，尽量在一个算子里面，实现多个需求功能
         * 3。尽量少对RDD进行shuffle算子操作，比如groupBykey、reduceBykey、sortByKey
         *         shuffle操作，会导致大量的磁盘读写，严重降低性能
         *         有shuffle的算子，和没有shuffle的算子，甚至性能相差极大
         *         有shuffle的算子，很容易造成性能倾斜，一旦数据倾斜，简直就是性能杀手
         * 4。无论做什么功能，性能第一
         *         在大数据项目中，性能最重要。主要是大数据以及大数据项目的特点，决定了大数据的程序和项目速度，都比较满
         *         如果不考虑性能的话，就会导致一个大数据处理程序运行长达数个小时，甚至是数个小时，对用户的体验，简直是
         *         一场灾难。
         */

        /**
         * 使用CountByKey算子实现随机抽取功能
         */
        randomExtractSession(taskId,filteredSessionRDD,sessionInfoPairRDD);

        //在使用Accumulutor之前，需要使用Action算子，否则获取的值为空，这里随机计算
        //filteredSessionRDD.count();
        //计算各个session占比,并写入MySQL
        calculateAndPersist(sessionAggrStatAccumulator.value(),taskId);
        //获取热门品类数据Top10
        List<Tuple2<CategorySortKey,String>> top10CategoryIds=getTop10Category(taskId,commonFullClickInfoRDD);
        //获取热门每一个品类点击Top10session
        getTop10Session(context,taskId,commonFullClickInfoRDD,top10CategoryIds);
        //关闭spark上下文
        context.close();
    }




    /**
     * 用于判断是否是生产环境
     * @param sc
     * @return
     */
    public static SQLContext getSQLContext(SparkContext sc)
    {
        boolean local= ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
        if(local)
        {
            return new SQLContext(sc);
        }
        return new HiveContext(sc);
    }

    private static void mock(JavaSparkContext context,SQLContext sc)
    {
        boolean local= ConfigurationManager.getBoolean(Constants.SPARK_LOCAL);
        
        if(local)
        {
            MockData.mock(context,sc);
        }
        

    }

    /**
     * 获取指定日期范围内的数据
     * @param sc
     * @param taskParam
     * @return
     */
    private static JavaRDD<Row> getActionRDD(SQLContext sc, JSONObject taskParam)
    {
        String startTime=ParamUtils.getParam(taskParam,Constants.PARAM_STARTTIME);
        String endTime=ParamUtils.getParam(taskParam,Constants.PARAM_ENDTIME);
        String sql="select *from user_visit_action where date>='"+startTime+"' and date<='"+endTime+"'";
        DataFrame df=sc.sql(sql);
        return df.javaRDD();
    }

    /**
     * 将数据进行映射成为Pair，键为SessionId，Value为Row
     * @param sessionRangeDate
     * @return
     */
    private static JavaPairRDD<String,Row> getSessonInfoPairRDD(JavaRDD<Row> sessionRangeDate) {
        return sessionRangeDate.mapToPair(new PairFunction<Row, String, Row>() {
            @Override
            public Tuple2<String, Row> call(Row row) throws Exception {
                return new Tuple2<String, Row>(row.getString(2),row);
            }
        });
    }

    /**
     * session粒度的聚合
     * @param sc
     * @param sessionInfoPairRDD
     * @return
     */
    private static JavaPairRDD<String,String> aggregateBySessionId(SQLContext sc, JavaPairRDD<String, Row> sessionInfoPairRDD) 
    {
        /**
         * 先将数据映射成map格式
         */
        /**
         *
         代码重构
        JavaPairRDD<String,Row> sessionActionPair=sessionRangeDate.mapToPair(new PairFunction<Row, String,Row>() {
            @Override
            public Tuple2<String, Row> call(Row row) throws Exception {
                return new Tuple2<String, Row>(row.getString(2),row);
            }
        });*/
        /**
         * 根据sessionId进行分组
         */
        JavaPairRDD<String,Iterable<Row>> sessionActionGrouped=sessionInfoPairRDD.groupByKey();

        JavaPairRDD<Long,String> sessionPartInfo=sessionActionGrouped.mapToPair(new PairFunction<Tuple2<String, Iterable<Row>>, Long, String>() 
        {
            @Override
            public Tuple2<Long, String> call(Tuple2<String, Iterable<Row>> stringIterableTuple2) throws Exception 
            {
                String sessionId=stringIterableTuple2._1;
                Iterable<Row> rows=stringIterableTuple2._2;
                StringBuffer searchKeywords=new StringBuffer();
                StringBuffer clickCategoryIds=new StringBuffer();
                Long userId=null;
                Date startTime=null;
                Date endTime=null;
                int stepLength=0;
                for (Row row:rows)
                {
                    if(userId==null) {
                        userId = row.isNullAt(1) ? -1L : row.getLong(1);
                    }
                    
                    String searchKeyword = row.isNullAt(5) ? null : row.getString(5);
                    Long clickCategoryId = row.isNullAt(6) ? -1L : row.getLong(6);


                    //判断是否需要拼接
                    if(StringUtils.isNotEmpty(searchKeyword))
                    {
                        if(!searchKeywords.toString().contains(searchKeyword))
                            searchKeywords.append(searchKeyword+",");
                    }

                    if(clickCategoryId!=null)
                    {
                        if(!clickCategoryId.toString().contains(String.valueOf(clickCategoryId)))
                            clickCategoryIds.append(String.valueOf(clickCategoryId)+",");
                    }

                    //计算session开始时间和结束时间
                    Date actionTime= DateUtils.parseTime(row.getString(4));
                    if(startTime==null)
                        startTime=actionTime;
                    if(endTime==null)
                        endTime=actionTime;
                    if(actionTime.before(startTime))
                    {
                        startTime=actionTime;
                    }
                    if(actionTime.after(endTime))
                    {
                        endTime=actionTime;
                    }
                        stepLength++;
                }
                //访问时长(s)
                Long visitLengtth = 0L;
                if (startTime != null && endTime != null) {
                    visitLengtth = (endTime.getTime() - startTime.getTime()) / 1000;
                }

                String searchKeywordsInfo = StringUtils.trimComma(searchKeywords.toString());
                String clickCategoryIdsInfo = StringUtils.trimComma(clickCategoryIds.toString());
                String info = Constants.FIELD_SESSIONID + "=" + sessionId + "|" + Constants.FIELD_SERACH_KEYWORDS + "=" + searchKeywordsInfo + "|"
                        + Constants.FIELD_CLICK_CATEGORYIDS + "=" + clickCategoryIdsInfo + "|" + Constants.FIELD_VISIT_LENGTH + "=" + visitLengtth + "|"
                        + Constants.FIELD_STEP_LENGTH + "=" + stepLength + "|" + Constants.FIELD_START_TIME + "=" + (startTime != null ? DateUtils.formatTime(startTime) : "");
                return new Tuple2<Long, String>(userId, info);
            }
        });

        //查询所有的用户数据
         String sql="select * from user_info";
         JavaRDD<Row> userInfoRDD=sc.sql(sql).javaRDD();
         //将用户信息映射成map
         JavaPairRDD<Long,Row> userInfoPariRDD=userInfoRDD.mapToPair(new PairFunction<Row, Long, Row>() {
             @Override
             public Tuple2<Long, Row> call(Row row) throws Exception {
                 return new Tuple2<Long, Row>(row.getLong(0),row);
             }
         });
         //将两个信息join在一起
        JavaPairRDD<Long,Tuple2<String,Row>> tuple2JavaPairRDD=sessionPartInfo.join(userInfoPariRDD);

        /**
         * 拿到所需的session
         */
        JavaPairRDD<String,String> sessionInfo=tuple2JavaPairRDD.mapToPair(new PairFunction<Tuple2<Long,Tuple2<String,Row>>, String, String>() {
            @Override
            public Tuple2<String, String> call(Tuple2<Long, Tuple2<String, Row>> longTuple2Tuple2) throws Exception {
                String sessionPartInfo=longTuple2Tuple2._2._1;
                Row userInfo=longTuple2Tuple2._2._2;
                //拿到需要的用户信息
                //拿到需要的用户信息
                int age = userInfo.isNullAt(3) ? -1 : userInfo.getInt(3);
                String professional = userInfo.isNullAt(4) ? "unknown" : userInfo.getString(4);
                String city = userInfo.isNullAt(5) ? "unknown" : userInfo.getString(5);
                String sex = userInfo.isNullAt(6) ? "unknown" : userInfo.getString(6);
                //拼接字符串
                String fullInfo=sessionPartInfo+"|"+Constants.FIELD_AGE+"="+age+"|"
                        +Constants.FIELD_PROFESSIONAL+"="+professional+"|"+Constants.FIELD_CITY+"="+city+"|"+Constants.FIELD_SEX+"="+sex;
                String session=StringUtils.getFieldFromConcatString(sessionPartInfo,"\\|",Constants.FIELD_SESSIONID);
                return new Tuple2<String, String>(session,fullInfo);
            }
        });

        return sessionInfo;
    }


    /**
     * 根据条件进行session的筛选
     * @param sessionInfoRDD
     * @param taskParam
     * @param sessionAggrStatAccumulator
     * @return
     */
    private static JavaPairRDD<String,String> filterSessionAndAggrStat(JavaPairRDD<String, String> sessionInfoRDD, final JSONObject taskParam, final Accumulator<String> sessionAggrStatAccumulator)
    {
        //得到条件
        String startAge=ParamUtils.getParam(taskParam,Constants.PARAM_STARTAGE);
        String endAge=ParamUtils.getParam(taskParam,Constants.PARAM_ENDAGE);
        String professionals=ParamUtils.getParam(taskParam,Constants.PARAM_PROFESSONALS);
        String cities=ParamUtils.getParam(taskParam,Constants.PARAM_CIYTIES);
        String sex= ParamUtils.getParam(taskParam,Constants.PARAM_SEX);
        String keyWords=ParamUtils.getParam(taskParam,Constants.PARAM_SERACH_KEYWORDS);
        String categoryIds=ParamUtils.getParam(taskParam,Constants.PARAM_CLICK_CATEGORYIDS);

        //拼接参数
        String _paramter=(startAge!=null?Constants.PARAM_STARTAGE+"="+startAge+"|":"")+
                (endAge!=null?Constants.PARAM_ENDAGE+"="+endAge+"|":"")+(professionals!=null?Constants.PARAM_PROFESSONALS+"="+professionals+"|":"")+
                (cities!=null?Constants.PARAM_CIYTIES+"="+cities+"|":"")+(sex!=null?Constants.PARAM_SEX+"="+sex+"|":"")+
                (keyWords!=null?Constants.PARAM_SERACH_KEYWORDS+"="+keyWords+"|":"")+(categoryIds!=null?Constants.PARAM_CLICK_CATEGORYIDS+"="+categoryIds+"|":"");

        if(_paramter.endsWith("\\|"))
            _paramter=_paramter.substring(0,_paramter.length()-1);

        final String paramter=_paramter;

        JavaPairRDD<String,String> filteredSessionRDD=sessionInfoRDD.filter(new Function<Tuple2<String, String>, Boolean>() 
        {
            @Override
            public Boolean call(Tuple2<String, String> tuple2) throws Exception 
            {
                String sessionInfo=tuple2._2;
                //按照条件进行过滤
                //按照年龄进行过滤
                if(!ValidUtils.between(sessionInfo,Constants.FIELD_AGE,paramter,Constants.PARAM_STARTAGE,Constants.PARAM_ENDAGE))
                    return  false;
                //按照职业进行过滤
                if(!ValidUtils.in(sessionInfo,Constants.FIELD_PROFESSIONAL,paramter,Constants.PARAM_PROFESSONALS))
                    return false;
                //按照城市进行过滤
                if(!ValidUtils.in(sessionInfo,Constants.FIELD_CITY,paramter,Constants.PARAM_CIYTIES))
                    return false;
                //按照性别进行筛选
                if(!ValidUtils.equal(sessionInfo,Constants.FIELD_SEX,paramter,Constants.PARAM_SEX))
                    return false;
                //按照搜索词进行过滤，只要有一个搜索词即可
                if(!ValidUtils.in(sessionInfo,Constants.FIELD_SERACH_KEYWORDS,paramter,Constants.PARAM_SERACH_KEYWORDS))
                    return false;
                if(!ValidUtils.in(sessionInfo,Constants.FIELD_CLICK_CATEGORYIDS,paramter,Constants.PARAM_CLICK_CATEGORYIDS))
                    return false;
                //如果经过了之前的所有的过滤条件，也就是满足用户筛选条件
                sessionAggrStatAccumulator.add(Constants.SESSION_COUNT);
                //计算出访问时长和访问步长的范围并进行相应的累加
                Long visitLength=Long.valueOf(StringUtils.getFieldFromConcatString(sessionInfo,"\\|",Constants.FIELD_VISIT_LENGTH));
                Long stepLength=Long.valueOf(StringUtils.getFieldFromConcatString(sessionInfo,"\\|",Constants.FIELD_STEP_LENGTH));
                //使用函数进行统计
                calculateVisitLength(visitLength);
                calculateStepLength(stepLength);
                return true;
            }

            //统计访问时长的数量
            private void calculateVisitLength(Long visitLegth)
            {
                if(visitLegth>=0&&visitLegth<=3)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1s_3s);
                else if(visitLegth>=4&&visitLegth<=6)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_4s_6s);
                else if(visitLegth>=7&&visitLegth<=9)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_7s_9s);
                else if(visitLegth>=10&&visitLegth<=30)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10s_30s);
                else if(visitLegth>30&&visitLegth<=60)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30s_60s);
                else if(visitLegth>60&&visitLegth<=180)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1m_3m);
                else if(visitLegth>180&&visitLegth<=600)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_3m_10m);
                else if(visitLegth>600&&visitLegth<=1800)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10m_30m);
                else if(visitLegth>1800)
                    sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30m);
            }
            //统计访问步长的数量
            private void calculateStepLength(Long stepLength)
            {
                if(stepLength>=1&&stepLength<=3)
                    sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_1_3);
                else if(stepLength>=4&&stepLength<=6)
                    sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_4_6);
                else if(stepLength>=7&&stepLength<=9)
                    sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_7_9);
                else if(stepLength>=10&&stepLength<=30)
                    sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_10_30);
                else if(stepLength>30&&stepLength<=60)
                    sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_30_60);
                else if(stepLength>60)
                sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_60);
            }
        });
        return filteredSessionRDD;
    }

    /**
     *
     * 获取过滤后的全信息RDD
     * @param filteredSessionRDD
     * @param sessionInfoPairRDD
     * @return
     */
    private static JavaPairRDD<String, Row> getFilterFullInfoRDD(JavaPairRDD<String, String> filteredSessionRDD, JavaPairRDD<String, Row> sessionInfoPairRDD) {
        //1.获取符合条件的session范围的所有品类
        return filteredSessionRDD.join(sessionInfoPairRDD).mapToPair(new PairFunction<Tuple2<String, Tuple2<String, Row>>, String, Row>() {
            @Override
            public Tuple2<String, Row> call(Tuple2<String, Tuple2<String, Row>> stringTuple2Tuple2) throws Exception {

                return new Tuple2<String, Row>(stringTuple2Tuple2._1,stringTuple2Tuple2._2._2);
            }
        });
    }
    /**
     * 随机抽取Sesison功能
     * @param taskId
     * @param filteredSessionRDD
     * @param sessionInfoPairRDD
     */
    private static void randomExtractSession(final Long taskId, JavaPairRDD<String, String> filteredSessionRDD, JavaPairRDD<String, Row> sessionInfoPairRDD) 
    {
        //1.先将过滤Seesion进行映射，映射成为Time,Info的数据格式
        final JavaPairRDD<String,String> mapDataRDD=filteredSessionRDD.mapToPair(new PairFunction<Tuple2<String, String>, String, String>() {
            @Override
            public Tuple2<String, String> call(Tuple2<String, String> tuple2) throws Exception {
                String info=tuple2._2;
                //获取开始的时间
                String startTime=StringUtils.getFieldFromConcatString(info,"\\|",Constants.FIELD_START_TIME);
                String formatStartTime=DateUtils.getDateHour(startTime);
                return new Tuple2<String, String>(formatStartTime,info);
            }
        });

        //计算每一个小时的Session数量
        Map<String,Object> mapCount=mapDataRDD.countByKey();

        //设计一个新的数据结构Map<String,Map<String,Long>> dateHourCount,日期作为Key，时间和数量作为Map
        Map<String,Map<String,Long>> dateHourCountMap=new HashMap<String, Map<String, Long>>();
        //遍历mapCount
        for (Map.Entry<String,Object> entry:mapCount.entrySet())
        {
            String date=entry.getKey().split("_")[0];
            String hour=entry.getKey().split("_")[1];

            Map<String,Long> hourCount=dateHourCountMap.get(date);
            if(hourCount==null)
            {
                hourCount=new HashMap<String, Long>();
                dateHourCountMap.put(date,hourCount);
            }
            hourCount.put(hour,(Long)entry.getValue());
        }
        //将数据按照天数平均
        int countPerday=100/dateHourCountMap.size();
        //实现一个随机函数后面将会用到

        Random random=new Random();
        //设计一个新的数据结构，用于存储随机索引,Key是每一天,Map是小时和随机索引列表构成的
        final Map<String,Map<String,List<Long>>> dateRandomExtractMap=new HashMap<String, Map<String, List<Long>>>();

        for (Map.Entry<String,Map<String,Long>> dateHourCount:dateHourCountMap.entrySet())
        {
            //日期
            String date=dateHourCount.getKey();
            //每一天个Session个数
            Long sessionCount=0L;
            for(Map.Entry<String,Long> hourCountMap:dateHourCount.getValue().entrySet())
            {
                sessionCount+=hourCountMap.getValue();
            }

            //获取每一天随机存储的Map
            Map<String,List<Long>> dayExtactMap=dateRandomExtractMap.get(date);
            if(dayExtactMap==null)
            {
                dayExtactMap=new HashMap<String, List<Long>>();
                dateRandomExtractMap.put(date,dayExtactMap);
            }

            //遍历每一个小时，计算出每一个小时的Session占比和抽取的数量

            for(Map.Entry<String,Long> hourCountMap:dateHourCount.getValue().entrySet())
            {
                int extractSize= (int) ((double) hourCountMap.getValue()/sessionCount*countPerday);

                //如果抽离的长度大于被抽取数据的长度，那么抽取的长度就是被抽取长度
                extractSize= extractSize>hourCountMap.getValue()?hourCountMap.getValue().intValue():extractSize;

                //获取存储每一个小时的List
                List<Long> indexList=dayExtactMap.get(hourCountMap.getKey());
                if(indexList==null)
                {
                    indexList=new ArrayList<Long>();
                    dayExtactMap.put(hourCountMap.getKey(),indexList);
                }

                //使用随机函数生成随机索引
                for(int i=0;i<extractSize;i++)
                {
                    int index=random.nextInt(hourCountMap.getValue().intValue());
                    //如果包含，那么一直循环直到不包含为止
                    while(indexList.contains(Long.valueOf(index)))
                        index=random.nextInt(hourCountMap.getValue().intValue());
                    indexList.add(Long.valueOf(index));
                }
            }
        }


        //2.将上面计算的RDD进行分组，然后使用FlatMap进行压平，然后判断是否在索引中，如果在，那么将这个信息持久化
        JavaPairRDD<String,Iterable<String>> time2GroupRDD=mapDataRDD.groupByKey();
        //将抽取的信息持久化到数据库，并返回SessionIds对，然后和以前的信息Join
        JavaPairRDD<String,String> sessionIds= time2GroupRDD.flatMapToPair(new PairFlatMapFunction<Tuple2<String,Iterable<String>>, String, String>() {
            @Override
            public Iterable<Tuple2<String, String>> call(Tuple2<String, Iterable<String>> tuple2) throws Exception {
                String dateStr=tuple2._1;
                String date=dateStr.split("_")[0];
                String hour=dateStr.split("_")[1];
                //使用一个List存储sessionId
                List<Tuple2<String,String>> sessionIds=new ArrayList<Tuple2<String, String>>();
                List<Long> indexList=dateRandomExtractMap.get(date).get(hour);
                //使用一个list保存需要持久化到数据库的对象
                List<SessionRandomExtract> sessionRandomExtractList=new ArrayList<SessionRandomExtract>();
                int index=0;
                for (String infos:tuple2._2) {
                    if(indexList.contains(Long.valueOf(index)))
                    {
                        //构建SessionRandomExtract
                        SessionRandomExtract sessionRandomExtract=new SessionRandomExtract();
                        final String sessionId=StringUtils.getFieldFromConcatString(infos,"\\|",Constants.FIELD_SESSIONID);
                        String startTime=StringUtils.getFieldFromConcatString(infos,"\\|",Constants.FIELD_START_TIME);
                        String searchKeyWards=StringUtils.getFieldFromConcatString(infos,"\\|",Constants.FIELD_SERACH_KEYWORDS);
                        String clickCategoryIds=StringUtils.getFieldFromConcatString(infos,"\\|",Constants.FIELD_CLICK_CATEGORYIDS);
                        sessionRandomExtract.set(taskId,sessionId,startTime,searchKeyWards,clickCategoryIds);
                        //添加到List中然后持久化到数据库中
                        sessionRandomExtractList.add(sessionRandomExtract);
                        sessionIds.add(new Tuple2<String, String>(sessionId,sessionId));
                    }
                    index++;
                }
                //持久化到数据库
                DaoFactory.getSessionRandomExtractDao().batchInsert(sessionRandomExtractList);
                return sessionIds;
            }
        });

        //3. 获取session的明细数据保存到数据库
        JavaPairRDD<String,Tuple2<String,Row>> sessionDetailRDD= sessionIds.join(sessionInfoPairRDD);
        sessionDetailRDD.foreachPartition(new VoidFunction<Iterator<Tuple2<String, Tuple2<String, Row>>>>() {
            @Override
            public void call(Iterator<Tuple2<String, Tuple2<String, Row>>> tuple2Iterator) throws Exception {
                List<SessionDetail> sessionDetailList=new ArrayList<SessionDetail>();
                while(tuple2Iterator.hasNext())
                {
                    Tuple2<String, Tuple2<String, Row>> tuple2=tuple2Iterator.next();
                    Row row=tuple2._2._2;
                    String sessionId=tuple2._1;
                    Long userId = row.isNullAt(1) ? -1L : row.getLong(1);
                    Long pageId = row.isNullAt(3) ? -1L : row.getLong(3);
                    String actionTime = row.isNullAt(4) ? null : row.getString(4);
                    String searchKeyWard = row.isNullAt(5) ? null : row.getString(5);
                    Long clickCategoryId = row.isNullAt(6) ? -1L : row.getLong(6);
                    Long clickProducetId = row.isNullAt(7) ? -1L : row.getLong(7);
                    String orderCategoryId = row.isNullAt(8) ? null : row.getString(8);
                    String orderProducetId = row.isNullAt(9) ? null : row.getString(9);
                    String payCategoryId = row.isNullAt(10) ? null : row.getString(10);
                    String payProducetId = row.isNullAt(11) ? null : row.getString(11);
                    SessionDetail sessionDetail=new SessionDetail();
                    sessionDetail.set(taskId,userId,sessionId,pageId,actionTime,searchKeyWard,clickCategoryId,clickProducetId,orderCategoryId,orderProducetId,payCategoryId,payProducetId);
                    sessionDetailList.add(sessionDetail);
                }
                DaoFactory.getSessionDetailDao().batchInsert(sessionDetailList);
            }
        });

    }

    //计算各个范围的占比，并持久化到数据库
    private static void calculateAndPersist(String value,Long taskId) 
    {
        //System.out.println(value);
        String sessionCountStr = StringUtils.getFieldFromConcatString(value,"\\|",Constants.SESSION_COUNT);
        Long sessionCount = (sessionCountStr == null || "null".equals(sessionCountStr) || sessionCountStr.isEmpty()) ? 0L : Long.valueOf(sessionCountStr);
        if (sessionCount == 0L) sessionCount = 1L; // Prevent division by zero

        //各个范围的访问时长
        String v1s3s = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_1s_3s);
        String v4s6s = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_4s_6s);
        String v7s9s = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_7s_9s);
        String v10s30s = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_10s_30s);
        String v30s60s = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_30s_60s);
        String v1m3m = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_1m_3m);
        String v3m10m = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_3m_10m);
        String v10m30m = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_10m_30m);
        String v30m = StringUtils.getFieldFromConcatString(value,"\\|",Constants.TIME_PERIOD_30m);

        Double visit_Length_1s_3s = (v1s3s == null || "null".equals(v1s3s) || v1s3s.isEmpty()) ? 0.0 : Double.valueOf(v1s3s);
        Double visit_Length_4s_6s = (v4s6s == null || "null".equals(v4s6s) || v4s6s.isEmpty()) ? 0.0 : Double.valueOf(v4s6s);
        Double visit_Length_7s_9s = (v7s9s == null || "null".equals(v7s9s) || v7s9s.isEmpty()) ? 0.0 : Double.valueOf(v7s9s);
        Double visit_Length_10s_30s = (v10s30s == null || "null".equals(v10s30s) || v10s30s.isEmpty()) ? 0.0 : Double.valueOf(v10s30s);
        Double visit_Length_30s_60s = (v30s60s == null || "null".equals(v30s60s) || v30s60s.isEmpty()) ? 0.0 : Double.valueOf(v30s60s);
        Double visit_Length_1m_3m = (v1m3m == null || "null".equals(v1m3m) || v1m3m.isEmpty()) ? 0.0 : Double.valueOf(v1m3m);
        Double visit_Length_3m_10m = (v3m10m == null || "null".equals(v3m10m) || v3m10m.isEmpty()) ? 0.0 : Double.valueOf(v3m10m);
        Double visit_Length_10m_30m = (v10m30m == null || "null".equals(v10m30m) || v10m30m.isEmpty()) ? 0.0 : Double.valueOf(v10m30m);
        Double visit_Length_30m = (v30m == null || "null".equals(v30m) || v30m.isEmpty()) ? 0.0 : Double.valueOf(v30m);

        //各个范围的访问步长
        String s13 = StringUtils.getFieldFromConcatString(value,"\\|",Constants.STEP_PERIOD_1_3);
        String s46 = StringUtils.getFieldFromConcatString(value,"\\|",Constants.STEP_PERIOD_4_6);
        String s79 = StringUtils.getFieldFromConcatString(value,"\\|",Constants.STEP_PERIOD_7_9);
        String s1030 = StringUtils.getFieldFromConcatString(value,"\\|",Constants.STEP_PERIOD_10_30);
        String s3060 = StringUtils.getFieldFromConcatString(value,"\\|",Constants.STEP_PERIOD_30_60);
        String s60 = StringUtils.getFieldFromConcatString(value,"\\|",Constants.STEP_PERIOD_60);

        Double step_Length_1_3 = (s13 == null || "null".equals(s13) || s13.isEmpty()) ? 0.0 : Double.valueOf(s13);
        Double step_Length_4_6 = (s46 == null || "null".equals(s46) || s46.isEmpty()) ? 0.0 : Double.valueOf(s46);
        Double step_Length_7_9 = (s79 == null || "null".equals(s79) || s79.isEmpty()) ? 0.0 : Double.valueOf(s79);
        Double step_Length_10_30 = (s1030 == null || "null".equals(s1030) || s1030.isEmpty()) ? 0.0 : Double.valueOf(s1030);
        Double step_Length_30_60 = (s3060 == null || "null".equals(s3060) || s3060.isEmpty()) ? 0.0 : Double.valueOf(s3060);
        Double step_Length_60 = (s60 == null || "null".equals(s60) || s60.isEmpty()) ? 0.0 : Double.valueOf(s60);

        //访问时长对应的sesison占比，保留3位小数
        double visit_Length_1s_3s_ratio=NumberUtils.formatDouble(visit_Length_1s_3s/sessionCount,3);
        double visit_Length_4s_6s_ratio=NumberUtils.formatDouble(visit_Length_4s_6s/sessionCount,3);
        double visit_Length_7s_9s_ratio=NumberUtils.formatDouble(visit_Length_7s_9s/sessionCount,3);
        double visit_Length_10s_30s_ratio=NumberUtils.formatDouble(visit_Length_10s_30s/sessionCount,3);
        double visit_Length_30s_60s_ratio=NumberUtils.formatDouble(visit_Length_30s_60s/sessionCount,3);
        double visit_Length_1m_3m_ratio=NumberUtils.formatDouble(visit_Length_1m_3m/sessionCount,3);
        double visit_Length_3m_10m_ratio=NumberUtils.formatDouble(visit_Length_3m_10m/sessionCount,3);
        double visit_Length_10m_30m_ratio=NumberUtils.formatDouble(visit_Length_10m_30m/sessionCount,3);
        double visit_Length_30m_ratio=NumberUtils.formatDouble(visit_Length_30m/sessionCount,3);

        //访问步长对应的session占比，保留3位小数
        double step_Length_1_3_ratio= NumberUtils.formatDouble(step_Length_1_3/sessionCount,3);
        double step_Length_4_6_ratio=NumberUtils.formatDouble(step_Length_4_6/sessionCount,3);
        double step_Length_7_9_ratio=NumberUtils.formatDouble(step_Length_7_9/sessionCount,3);
        double step_Length_10_30_ratio =NumberUtils.formatDouble(step_Length_10_30/sessionCount,3);
        double step_Length_30_60_ratio=NumberUtils.formatDouble(step_Length_30_60/sessionCount,3);
        double step_Length_60_ratio=NumberUtils.formatDouble(step_Length_60/sessionCount,3);

        SessionAggrStat sessionAggrStat=new SessionAggrStat();
        sessionAggrStat.set(taskId,sessionCount,visit_Length_1s_3s_ratio,visit_Length_4s_6s_ratio,
                visit_Length_7s_9s_ratio,visit_Length_10s_30s_ratio,visit_Length_30s_60s_ratio,
                visit_Length_1m_3m_ratio,visit_Length_3m_10m_ratio,visit_Length_10m_30m_ratio,visit_Length_30m_ratio
        ,step_Length_1_3_ratio,step_Length_4_6_ratio,step_Length_7_9_ratio,step_Length_10_30_ratio,step_Length_30_60_ratio,step_Length_60_ratio);
        List<SessionAggrStat> sessionAggrStatList=new ArrayList<SessionAggrStat>();
        sessionAggrStatList.add(sessionAggrStat);
        // 插入数据库
        // 先删除旧数据，防止主键冲突
        DaoFactory.getSessionAggrStatDao().deleteByTaskId(taskId);
        DaoFactory.getSessionAggrStatDao().batchInsert(sessionAggrStatList);
    }


    /**
     * 获取top10热门品类
     * @param taskId
     * @param sessionId2DetailRDD
     */
    private static List<Tuple2<CategorySortKey,String>> getTop10Category(Long taskId, JavaPairRDD<String, Row> sessionId2DetailRDD) 
    {
        //1.第一步已抽离出方法getFilterFullInfoRDD
        //2。获取访问的品类id，访问过表示点击，下单，支付
        JavaPairRDD<Long,Long> categoryRDD=sessionId2DetailRDD.flatMapToPair(new PairFlatMapFunction<Tuple2<String,Row>, Long, Long>() {
            @Override
            public Iterable<Tuple2<Long, Long>> call(Tuple2<String, Row> stringRowTuple2) throws Exception {
                Row row=stringRowTuple2._2;
                List<Tuple2<Long,Long>> visitCategoryList=new ArrayList<Tuple2<Long, Long>>();
                Long clickCategoryId = row.isNullAt(6) ? -1L : row.getLong(6);
                //点击品类的id
                if(clickCategoryId != -1L)
                    visitCategoryList.add(new Tuple2<Long, Long>(clickCategoryId,clickCategoryId));

                String orderCategoryIds = row.isNullAt(8) ? null : row.getString(8);
                if(orderCategoryIds != null){
                    String[] orderCategoryIdsSplited = orderCategoryIds.split(",");
                    for (String orderCategoryId : orderCategoryIdsSplited) {
                        if (orderCategoryId != null && !"null".equals(orderCategoryId) && !orderCategoryId.isEmpty()) {
                            visitCategoryList.add(new Tuple2<Long, Long>(Long.valueOf(orderCategoryId), Long.valueOf(orderCategoryId)));
                        }
                    }
                }

                String payCategoryIds = row.isNullAt(10) ? null : row.getString(10);
                if(payCategoryIds != null){
                    String[] payCategoryIdsSplited = payCategoryIds.split(",");
                    for (String payCategoryId : payCategoryIdsSplited) {
                        if (payCategoryId != null && !"null".equals(payCategoryId) && !payCategoryId.isEmpty()) {
                            visitCategoryList.add(new Tuple2<Long, Long>(Long.valueOf(payCategoryId), Long.valueOf(payCategoryId)));
                        }
                    }
                }
                return visitCategoryList;
            }
        });

        //需要去重
         categoryRDD=categoryRDD.distinct();
        //3。计算各个品类的点击，下单和支付次数
        // 3.1 计算点击品类的数量
        JavaPairRDD<Long,Long> clickCategoryRDD = getLClickCategoryRDD(sessionId2DetailRDD);

        // 3.2 计算下单的品类的数量
        JavaPairRDD<Long,Long> orderCategoryRDD= getOrderCategoryRDD(sessionId2DetailRDD);

        // 3.3 计算支付的品类的数量
        JavaPairRDD<Long,Long> payCategoryRDD=getPayCategoryRDD(sessionId2DetailRDD);

        //4.将上述计算的三个字段进行join，注意这里是LeftOuterJoin，因为有些品类只是点击了
        JavaPairRDD<Long,String> categoryCountRDD=joinCategoryAndData(categoryRDD,clickCategoryRDD,orderCategoryRDD,payCategoryRDD);

        //5.自定义二次排序的key
        JavaPairRDD<CategorySortKey,String> sortKeyCountRDD=categoryCountRDD.mapToPair(new PairFunction<Tuple2<Long, String>, CategorySortKey, String>() {
            @Override
            public Tuple2<CategorySortKey, String> call(Tuple2<Long, String> longStringTuple2) throws Exception {
                String countInfo=longStringTuple2._2;
                String clickCountStr = StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_CLICK_CATEGORY);
                String orderCountStr = StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_ORDER_CATEGORY);
                String payCountStr = StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_PAY_CATEGORY);

                Long clickCount = (clickCountStr == null || "null".equals(clickCountStr) || clickCountStr.isEmpty()) ? 0L : Long.valueOf(clickCountStr);
                Long orderCount = (orderCountStr == null || "null".equals(orderCountStr) || orderCountStr.isEmpty()) ? 0L : Long.valueOf(orderCountStr);
                Long payCount = (payCountStr == null || "null".equals(payCountStr) || payCountStr.isEmpty()) ? 0L : Long.valueOf(payCountStr);
                
                CategorySortKey key=new CategorySortKey();
                key.set(clickCount,orderCount,payCount);
                return new Tuple2<CategorySortKey, String>(key,countInfo);
            }
        });

        JavaPairRDD<CategorySortKey,String> sortedCategoryRDD=sortKeyCountRDD.sortByKey(false);
        //取出前10个，写入数据库
        List<Tuple2<CategorySortKey,String>> top10CategoryList=sortedCategoryRDD.take(10);
        List<Top10Category> top10Categories=new ArrayList<Top10Category>();
        for(Tuple2<CategorySortKey,String> tuple2:top10CategoryList)
        {
            String countInfo=tuple2._2;
            String categoryIdStr = StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_CATEGORY_ID);
            String clickCountStr = StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_CLICK_CATEGORY);
            String orderCountStr = StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_ORDER_CATEGORY);
            String payCountStr = StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_PAY_CATEGORY);

            Long categoryId = (categoryIdStr == null || "null".equals(categoryIdStr) || categoryIdStr.isEmpty()) ? -1L : Long.valueOf(categoryIdStr);
            Long clickCount = (clickCountStr == null || "null".equals(clickCountStr) || clickCountStr.isEmpty()) ? 0L : Long.valueOf(clickCountStr);
            Long orderCount = (orderCountStr == null || "null".equals(orderCountStr) || orderCountStr.isEmpty()) ? 0L : Long.valueOf(orderCountStr);
            Long payCount = (payCountStr == null || "null".equals(payCountStr) || payCountStr.isEmpty()) ? 0L : Long.valueOf(payCountStr);
            
            Top10Category top10Category=new Top10Category();
            top10Category.set(taskId,categoryId,clickCount,orderCount,payCount);
            top10Categories.add(top10Category);
        }
        //插入数据库
        DaoFactory.getTop10CategoryDao().deleteByTaskId(taskId);
        DaoFactory.getTop10CategoryDao().batchInsert(top10Categories);
        return top10CategoryList;
    }



    /**
     * 将几个品类相连接
     * @param categoryRDD
     * @param clickCategoryRDD
     * @param orderCategoryRDD
     * @param payCategoryRDD
     * @return
     */
    private static JavaPairRDD<Long,String> joinCategoryAndData(JavaPairRDD<Long, Long> categoryRDD, JavaPairRDD<Long, Long> clickCategoryRDD, JavaPairRDD<Long, Long> orderCategoryRDD, JavaPairRDD<Long, Long> payCategoryRDD) 
    {
        JavaPairRDD<Long, Tuple2<Long, com.google.common.base.Optional<Long>>> tmpJoinRDD=categoryRDD.leftOuterJoin(clickCategoryRDD);

        JavaPairRDD<Long,String> tmpRDD=tmpJoinRDD.mapToPair(new PairFunction<Tuple2<Long, Tuple2<Long, com.google.common.base.Optional<Long>>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<Long, com.google.common.base.Optional<Long>>> longTuple2Tuple2) throws Exception {
                Long categoryId=longTuple2Tuple2._1;
                com.google.common.base.Optional<Long> clickIOptional=longTuple2Tuple2._2._2;
                Long clickCount=0L;
                if(clickIOptional.isPresent())
                {
                    clickCount=clickIOptional.get();
                }

                String value=Constants.FIELD_CATEGORY_ID+"="+categoryId+"|"+Constants.FIELD_CLICK_CATEGORY+"="+clickCount;
                return new Tuple2<Long, String>(categoryId,value);
            }
        });
        //join下单的次数
        tmpRDD=tmpRDD.leftOuterJoin(orderCategoryRDD).mapToPair(new PairFunction<Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>> longTuple2Tuple2) throws Exception {
                Long categoryId=longTuple2Tuple2._1;
                com.google.common.base.Optional<Long> clickIOptional=longTuple2Tuple2._2._2;
                Long clickCount=0L;
                String value=longTuple2Tuple2._2._1;
                if(clickIOptional.isPresent())
                {
                    clickCount=clickIOptional.get();
                }

                value=value+"|"+Constants.FIELD_ORDER_CATEGORY+"="+clickCount;
                return new Tuple2<Long, String>(categoryId,value);
            }
        });
        //join支付的次数
        tmpRDD=tmpRDD.leftOuterJoin(payCategoryRDD).mapToPair(new PairFunction<Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>> longTuple2Tuple2) throws Exception {
                Long categoryId=longTuple2Tuple2._1;
                com.google.common.base.Optional<Long> clickIOptional=longTuple2Tuple2._2._2;
                Long clickCount=0L;
                String value=longTuple2Tuple2._2._1;
                if(clickIOptional.isPresent())
                {
                    clickCount=clickIOptional.get();
                }

                value=value+"|"+Constants.FIELD_PAY_CATEGORY+"="+clickCount;
                return new Tuple2<Long, String>(categoryId,value);
            }
        });
        return tmpRDD;
    }

    //后去支付品类RDD
    private static JavaPairRDD<Long,Long> getPayCategoryRDD(JavaPairRDD<String, Row> sessionId2DetailRDD) {
        JavaPairRDD<String,Row> payActionRDD=sessionId2DetailRDD.filter(new Function<Tuple2<String, Row>, Boolean>() {
            @Override
            public Boolean call(Tuple2<String, Row> stringRowTuple2) throws Exception {
                Row row=stringRowTuple2._2;
                String categoryIds=row.getString(10);
                if(categoryIds==null||"".equals(categoryIds)) return  false;
                return true;
            }
        });
        //映射成为新的Pair
        JavaPairRDD<Long,Long> payCategoryRDD=payActionRDD.flatMapToPair(new PairFlatMapFunction<Tuple2<String, Row>, Long, Long>() {
            @Override
            public Iterable<Tuple2<Long, Long>> call(Tuple2<String, Row> stringRowTuple2) throws Exception {
                List<Tuple2<Long,Long>> orderCategoryIds=new ArrayList<Tuple2<Long, Long>>();
                Row row=stringRowTuple2._2;
                String payCategoryIds = row.isNullAt(10) ? null : row.getString(10);
                if (payCategoryIds != null) {
                    String payCategoryIdsSplited[] = payCategoryIds.split(",");
                    for (String payCategoryId : payCategoryIdsSplited) {
                        if (payCategoryId != null && !"null".equals(payCategoryId) && !payCategoryId.isEmpty()) {
                            orderCategoryIds.add(new Tuple2<Long, Long>(Long.valueOf(payCategoryId), 1L));
                        }
                    }
                }
                return orderCategoryIds;
            }
        });

        //计算次数
        JavaPairRDD<Long,Long>  payCategoryCountRDD=payCategoryRDD.reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long aLong, Long aLong2) throws Exception {
                return aLong+aLong2;
            }
        });

        return payCategoryCountRDD;
    }

    //获取下单品类RDD
    private static JavaPairRDD<Long,Long> getOrderCategoryRDD(JavaPairRDD<String, Row> sessionId2DetailRDD) {
        JavaPairRDD<String,Row> orderActionRDD=sessionId2DetailRDD.filter(new Function<Tuple2<String, Row>, Boolean>() {
            @Override
            public Boolean call(Tuple2<String, Row> stringRowTuple2) throws Exception {
                Row row=stringRowTuple2._2;
                String categoryIds=row.getString(8);
                if(categoryIds==null||"".equals(categoryIds)) return  false;
                return true;
            }
        });
        //映射成为新的Pair
        JavaPairRDD<Long,Long> orderCategoryRDD=orderActionRDD.flatMapToPair(new PairFlatMapFunction<Tuple2<String, Row>, Long, Long>() {
            @Override
            public Iterable<Tuple2<Long, Long>> call(Tuple2<String, Row> stringRowTuple2) throws Exception {
                List<Tuple2<Long,Long>> orderCategoryIds=new ArrayList<Tuple2<Long, Long>>();
                Row row=stringRowTuple2._2;
                String orderCategoryIdsRaw = row.isNullAt(8) ? null : row.getString(8);
                if (orderCategoryIdsRaw != null) {
                    String orderCategoryIdsSplited[] = orderCategoryIdsRaw.split(",");
                    for (String orderCategoryId : orderCategoryIdsSplited) {
                        if (orderCategoryId != null && !"null".equals(orderCategoryId) && !orderCategoryId.isEmpty()) {
                            orderCategoryIds.add(new Tuple2<Long, Long>(Long.valueOf(orderCategoryId), 1L));
                        }
                    }
                }
                return orderCategoryIds;
            }
        });

        //计算次数
        JavaPairRDD<Long,Long>  orderCategoryCountRDD=orderCategoryRDD.reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long aLong, Long aLong2) throws Exception {
                return aLong+aLong2;
            }
        });

        return orderCategoryCountRDD;
    }

    //获取点击品类RDD
    private static JavaPairRDD<Long,Long> getLClickCategoryRDD(JavaPairRDD<String, Row> sessionId2DetailRDD) {
        JavaPairRDD<String,Row> clickActionRDD=sessionId2DetailRDD.filter(new Function<Tuple2<String, Row>, Boolean>() {
            @Override
            public Boolean call(Tuple2<String, Row> stringRowTuple2) throws Exception {
                Row row=stringRowTuple2._2;
                if(row.get(6)==null) return false;
                return true;
            }
        });
        //映射成为新的Pair
        JavaPairRDD<Long,Long> clickCategoryRDD=clickActionRDD.mapToPair(new PairFunction<Tuple2<String,Row>, Long,Long>() {
            @Override
            public Tuple2<Long,Long> call(Tuple2<String, Row> stringRowTuple2) throws Exception {
                Row row = stringRowTuple2._2;
                Long categoryId = row.isNullAt(6) ? -1L : row.getLong(6);
                return new Tuple2<Long, Long>(categoryId, 1L);
            }
        });

        //计算次数
        JavaPairRDD<Long,Long>  clickCategoryCountRDD=clickCategoryRDD.reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long aLong, Long aLong2) throws Exception {
                return aLong+aLong2;
            }
        });
        return clickCategoryCountRDD;
    }


    //获取每一个品类的Session Top10
    private static void getTop10Session(JavaSparkContext sc, final Long taskId, JavaPairRDD<String, Row> sessionInfoPairRDD, List<Tuple2<CategorySortKey, String>> top10CategoryIds) {
        List<Tuple2<Long,Long>> categoryIdList=new ArrayList<Tuple2<Long, Long>>();
        for(Tuple2<CategorySortKey, String> top10CategoryId:top10CategoryIds)
        {
            String countInfo=top10CategoryId._2;
            Long categoryId=Long.valueOf(StringUtils.getFieldFromConcatString(countInfo,"\\|",Constants.FIELD_CATEGORY_ID));
            categoryIdList.add(new Tuple2<Long, Long>(categoryId,categoryId));
        }
        //生成一份RDD
        JavaPairRDD<Long,Long> top10CategoryIdsRDD=sc.parallelizePairs(categoryIdList);
        //按照SessionId进行分组
        JavaPairRDD<String,Iterable<Row>> gourpBySessionIdRDD=sessionInfoPairRDD.groupByKey();
        //计算每一个session对品类的点击次数
        JavaPairRDD<Long,String> categorySessionCountRDD=gourpBySessionIdRDD.flatMapToPair(new PairFlatMapFunction<Tuple2<String, Iterable<Row>>, Long, String>() {
            @Override
            public Iterable<Tuple2<Long, String>> call(Tuple2<String, Iterable<Row>> tuple2) throws Exception {
                String sessionId=tuple2._1;
                //保存每一个品类的单击次数
                Map<Long,Long> categoryIdCount=new HashMap<Long, Long>();
                for(Row row:tuple2._2)
                {
                    if(row.get(6)!=null)
                    {
                        Long count=categoryIdCount.get(row.getLong(6));
                        if(count==null)
                        {
                            count=0L;
                        }
                        count++;
                        categoryIdCount.put(row.getLong(6),count);
                    }
                }
                List<Tuple2<Long,String>>  categoryIdCountList=new ArrayList<Tuple2<Long,String>>();
                for(Map.Entry<Long,Long> entry:categoryIdCount.entrySet())
                {
                    //将数据拼接
                    String value=sessionId+","+entry.getValue();
                    categoryIdCountList.add(new Tuple2<Long,String>(entry.getKey(),value));
                }
                return categoryIdCountList;
            }
        });

        //将top10的品类与上面计算的RDD相join 得到每一个热门品类的点击次数
        JavaPairRDD<Long,String> top10CategorySessionCountRDD=top10CategoryIdsRDD.join(categorySessionCountRDD).mapToPair(new PairFunction<Tuple2<Long, Tuple2<Long, String>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<Long, String>> tuple2) throws Exception {
                return new Tuple2<Long,String>(tuple2._1,tuple2._2._2);
            }
        });

        //根据品类分组
        JavaPairRDD<Long,Iterable<String>> top10CategorySessionCountGroupRDD=top10CategorySessionCountRDD.groupByKey();

        JavaPairRDD<String,String> top10CategorySessionRDD=top10CategorySessionCountGroupRDD.flatMapToPair(new PairFlatMapFunction<Tuple2<Long,Iterable<String>>, String,String>() {
            @Override
            public Iterable<Tuple2<String, String>> call(Tuple2<Long, Iterable<String>> tuple2) throws Exception {
                List<Top10CategorySession> top10CategorySessionList=new ArrayList<Top10CategorySession>();
                Long categoryId=tuple2._1;
                String[] top10Sessions=new String[10];
                List<Tuple2<String,String>> sessionIdList=new ArrayList<Tuple2<String, String>>();
                for (String sessionCount:tuple2._2)
                {
                    String[] sessionCountSplited=sessionCount.split(",");
                    //String sessionId=sessionCountSplited[0];
                    Long count=Long.valueOf(sessionCountSplited[1]);
                    //获取TopN
                    for(int i=0;i<top10Sessions.length;i++)
                    {
                        if(top10Sessions[i]==null)
                        {
                            top10Sessions[i]=sessionCount;
                            break;
                        }
                        else
                        {
                            Long _count=Long.valueOf(top10Sessions[i].split(",")[1]);
                            if(count>_count)
                            {
                                for (int j = 9; j>i ; j--) {
                                    top10Sessions[j]=top10Sessions[j-1];
                                }
                                top10Sessions[i]=sessionCount;
                                break;
                            }
                        }
                    }
                }
                //封装数据
                for (int i=0;i<top10Sessions.length;i++)
                {
                    if(top10Sessions[i]!=null)
                    {
                        Top10CategorySession top10CategorySession=new Top10CategorySession();
                        String sessionId=top10Sessions[i].split(",")[0];
                        Long count=Long.valueOf(top10Sessions[i].split(",")[1]);
                        top10CategorySession.set(taskId,categoryId,sessionId,count);
                        top10CategorySessionList.add(top10CategorySession);
                        sessionIdList.add(new Tuple2<String, String>(sessionId,sessionId));
                    }
                }
                //批量插入数据库
                DaoFactory.getTop10CategorySessionDao().deleteByTaskId(taskId);
                DaoFactory.getTop10CategorySessionDao().batchInsert(top10CategorySessionList);
                return sessionIdList;
            }
        });


        //3. 获取session的明细数据保存到数据库
        JavaPairRDD<String,Tuple2<String,Row>> sessionDetailRDD= top10CategorySessionRDD.join(sessionInfoPairRDD);
        sessionDetailRDD.foreachPartition(new VoidFunction<Iterator<Tuple2<String, Tuple2<String, Row>>>>() {
            @Override
            public void call(Iterator<Tuple2<String, Tuple2<String, Row>>> tuple2Iterator) throws Exception {
                List<SessionDetail> sessionDetailList=new ArrayList<SessionDetail>();
                while(tuple2Iterator.hasNext())
                {
                    Tuple2<String, Tuple2<String, Row>> tuple2=tuple2Iterator.next();
                    Row row=tuple2._2._2;
                    String sessionId=tuple2._1;
                    Long userId = row.isNullAt(1) ? -1L : row.getLong(1);
                    Long pageId = row.isNullAt(3) ? -1L : row.getLong(3);
                    String actionTime = row.isNullAt(4) ? null : row.getString(4);
                    String searchKeyWard = row.isNullAt(5) ? null : row.getString(5);
                    Long clickCategoryId = row.isNullAt(6) ? -1L : row.getLong(6);
                    Long clickProducetId = row.isNullAt(7) ? -1L : row.getLong(7);
                    String orderCategoryId = row.isNullAt(8) ? null : row.getString(8);
                    String orderProducetId = row.isNullAt(9) ? null : row.getString(9);
                    String payCategoryId = row.isNullAt(10) ? null : row.getString(10);
                    String payProducetId = row.isNullAt(11) ? null : row.getString(11);
                    SessionDetail sessionDetail=new SessionDetail();
                    sessionDetail.set(taskId,userId,sessionId,pageId,actionTime,searchKeyWard,clickCategoryId,clickProducetId,orderCategoryId,orderProducetId,payCategoryId,payProducetId);
                    sessionDetailList.add(sessionDetail);
                }
                DaoFactory.getSessionDetailDao().batchInsert(sessionDetailList);
            }
        });
    }
}
