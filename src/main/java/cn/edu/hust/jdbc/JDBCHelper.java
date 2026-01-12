package cn.edu.hust.jdbc;

import cn.edu.hust.conf.ConfigurationManager;
import cn.edu.hust.constant.Constants;

import java.sql.*;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public class JDBCHelper {
    private static JDBCHelper instance=new JDBCHelper();
    //使用阻塞队列
    private LinkedBlockingQueue<Connection> queue=new LinkedBlockingQueue<Connection>();
    static{
        try {
            Class.forName(ConfigurationManager.getProperty(Constants.JDBC_DRIVER));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
    /**
     *  在构造函数创建数据库连接池
     *  结合单例模式，确保数据库连接池单例
     */
    private JDBCHelper()
    {
        int dataSourceSize=ConfigurationManager.getInteger(Constants.JDBC_ACTIVE);
        String url=ConfigurationManager.getProperty(Constants.JDBC_URL);
        String username=ConfigurationManager.getProperty(Constants.JDBC_USERNAME);
        String passward=ConfigurationManager.getProperty(Constants.JDBC_PASSWORD);
        try
        {
            for(int i=0;i<dataSourceSize;i++)
            {
                Connection connection=DriverManager.getConnection(url,username,passward);
                queue.put(connection);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    };

    public static JDBCHelper getInstance()
    {
        return instance;
    }

    /**
     * 获取数据库连接
     * 使用阻塞队列
     * @return
     */
    public Connection getConnection()
    {
        try {
            return queue.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 更新数据
     * @param sql
     * @param params
     * @return
     */
    public int excuteUpdate(String sql,Object[] params)
    {
        int re=0;
        Connection conn=null;
        PreparedStatement statement=null;
        try
        {
            conn=getConnection();
            statement=conn.prepareStatement(sql);
            if(params != null && params.length > 0) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i+1,params[i]);
                }
            }
            re=statement.executeUpdate();
            return re;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally {
            if(statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if(conn!=null)
            {
                try {
                    conn.setAutoCommit(true);
                    queue.put(conn);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return  re;
    }


    public static interface QueryCallBack
    {
        void process(ResultSet rs);
    }

    /**
     * 查询数据的处理
     * 使用接口回掉，根据用户的自定义接口来进行处理
     * @param sql
     * @param params
     * @param queryCallBack
     */
    public void excuteQuery(String sql,Object[] params,QueryCallBack queryCallBack)
    {
        Connection conn=null;
        PreparedStatement statement=null;
        ResultSet rs = null;
        try
        {
            conn=getConnection();
            statement=conn.prepareStatement(sql);
            if(params != null && params.length > 0) {
                for (int i = 0; i < params.length; i++) 
                {
                    statement.setObject(i+1,params[i]);
                }
            }
            rs=statement.executeQuery();
            queryCallBack.process(rs);
        }catch (Exception e)
        {
            e.printStackTrace();
        }
        finally {
            if(rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if(statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if(conn!=null)
            {
                try {
                    conn.setAutoCommit(true);
                    queue.put(conn);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 批量执行sql语句
     * @param sql
     * @param params
     * @return
     */
    public int[] excuteBatch(String sql,List<Object[]> params)
    {
        Connection connection=null;
        PreparedStatement statement=null;
        int[] res=null;
        try
        {
            connection=getConnection();
            statement=connection.prepareStatement(sql);
            //1.取消自动提交
            connection.setAutoCommit(false);
            //2.设置参数
            if(params != null && params.size() > 0) {
                for (Object[] param: params) {
                    for (int i = 0; i < param.length; i++) {
                        statement.setObject(i+1,param[i]);
                    }
                    statement.addBatch();
                }
            }
            //3.批量执行
            res=statement.executeBatch();
            //4.最后一步提交
            connection.commit();
            return res;

        } catch (Exception e) {
            e.printStackTrace();
            if(connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
        }
        finally {
            if(statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            if(connection!=null)
            {
                try {
                    connection.setAutoCommit(true);
                    queue.put(connection);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return res;
    }
}
