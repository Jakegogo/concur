package transfer.test;

import com.baidu.bjf.remoting.protobuf.Codec;
import com.baidu.bjf.remoting.protobuf.ProtobufProxy;
import com.caucho.hessian.io.Hessian2Output;
import transfer.ByteArray;
import transfer.Transfer;
import transfer.def.TransferConfig;
import utils.JsonUtils;
import utils.ProtostuffUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * Created by Administrator on 2015/2/26.
 */
public class TestEncodePerform {

    public static void main(String[] args) {

        TransferConfig.registerClass(Entity.class, 1);
        
        Transfer.encodePreCompile(Entity.class);

        Entity entity = new Entity();
        entity.setId(1L);
        entity.setName("Jake");
        entity.setStr("str");
        entity.setBool(true);
        entity.setUid(101);
        entity.getFriends().add(1l);
        entity.getFriends().add(2l);
        entity.getFriends().add(3l);

        long t1 = 0l;

        Codec<Entity> simpleTypeCodec = ProtobufProxy
                .create(Entity.class);

        try {
            byte[] bb0 = simpleTypeCodec.encode(entity);
            System.out.println(bb0.length);

            t1 = System.currentTimeMillis();
            for (int i = 0; i < 10000000;i++) {

                // 序列化
                byte[] bb = simpleTypeCodec.encode(entity);
                // 反序列化
    //            SimpleTypeTest newStt = simpleTypeCodec.decode(bb);
            }
            System.out.println("protobuff : " + (System.currentTimeMillis() - t1));
        } catch (IOException e) {
            e.printStackTrace();
        }


        t1 = System.currentTimeMillis();
        ByteArray byteArray = Transfer.encode(entity, Entity.class);
        System.out.println(byteArray.toBytes().length);
        for (int i = 0; i < 10000000;i++) {
            byteArray = Transfer.encode(entity, Entity.class);
        }
        System.out.println("the transfer : " + (System.currentTimeMillis() - t1));


        byte[] bytes0 = ProtostuffUtils.object2Bytes(entity);
        System.out.println(bytes0.length);
        t1 = System.currentTimeMillis();
        for (int i = 0; i < 10000000;i++) {
            byte[] bytes = ProtostuffUtils.object2Bytes(entity);
        }
        System.out.println("protostuff : " + (System.currentTimeMillis() - t1));



        // hessian
        // 序列化


        t1 = System.currentTimeMillis();
        try {
            for (int i = 0; i < 10000000;i++) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                Hessian2Output h2o = new Hessian2Output(os);
                h2o.startMessage();
                h2o.writeObject(entity);
                h2o.completeMessage();
                h2o.close();

                byte[] buffer = os.toByteArray();
                os.close();
                if (i == 0) {
                    System.out.println("hessian length:" + buffer.length);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("hessian : " + (System.currentTimeMillis() - t1));


        // java序列化
        t1 = System.currentTimeMillis();
        try {
            for (int i = 0; i < 10000000;i++) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(os);
                out.writeObject(entity);
                byte[] buffer = os.toByteArray();
                if (i == 0) {
                    System.out.println("java length:" + buffer.length);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("java : " + (System.currentTimeMillis() - t1));


        t1 = System.currentTimeMillis();
        for (int i = 0; i < 10000000;i++) {
            JsonUtils.object2Bytes(entity);
        }
        System.out.println("fastjson to bytes : " + (System.currentTimeMillis() - t1));



        t1 = System.currentTimeMillis();
        for (int i = 0; i < 10000000;i++) {
            JsonUtils.object2JsonString(entity).getBytes();
        }
        System.out.println("fastjson to string : " + (System.currentTimeMillis() - t1));



        
        t1 = System.currentTimeMillis();
        for (int i = 0; i < 10000000;i++) {
        	JacksonUtils.object2JsonString(entity);
        }
        System.out.println("jackson : " + (System.currentTimeMillis() - t1));
        
        
        
        

        System.out.println(JsonUtils.object2JsonString(entity).getBytes().length);
        System.out.println(JsonUtils.object2Bytes(entity).length);




    }

}