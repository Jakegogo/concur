package transfer.test;

import java.util.Date;

import transfer.Transfer;

/**
 * Created by Administrator on 2015/2/26.
 */
public class TestEncodeProfile1 {

    public static void main(String[] args) {

        Entity1 entity = new Entity1();
        entity.setId(System.currentTimeMillis());
        entity.setUid(-101);
        entity.setFriends(null);

        Entity1 next = Transfer.decode(Transfer.encode(entity, Entity1.class), Entity1.class);

        next.setNext(Transfer.decode(Transfer.encode(entity, Entity1.class), Entity1.class));

        entity.setNext(next);
        

        System.out.println("length:" + Transfer.encode(entity, Entity1.class).toBytes().length);

        long t1 = System.currentTimeMillis();

        for (int i = 0; i < 1000000;i++) {
            Transfer.encode(entity, Entity1.class);
        }

        System.out.println((System.currentTimeMillis() - t1) + "ms");


    }

}
