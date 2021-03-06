package LASER.mapreduce.preparation;

import LASER.Utils.LaserUtils;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.mahout.cf.taste.hadoop.EntityPrefWritable;
import org.apache.mahout.math.*;

import java.io.IOException;
import java.util.Iterator;

public class ToUserVectorReducer extends Reducer<VarIntWritable, VectorWritable, VarIntWritable, VectorWritable> {

    @Override
    protected void reduce(VarIntWritable userId, Iterable<VectorWritable> preferences, Context context)
            throws IOException, InterruptedException {

        VectorWritable vw = VectorWritable.merge(preferences.iterator());
        vw.setWritesLaxPrecision(true);

        context.write(userId, vw);
    }
}
