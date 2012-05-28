package LASER;

import LASER.Utils.HDFSUtil;
import LASER.Utils.HadoopUtil;
import LASER.mapreduce.preparation.ToItemVectorMapper;
import LASER.mapreduce.preparation.ToItemVectorReducer;
import LASER.mapreduce.preparation.ToUserVectorMapper;
import LASER.mapreduce.preparation.ToUserVectorReducer;
import LASER.mapreduce.recommendation.*;
import LASER.mapreduce.similarity.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.mahout.cf.taste.hadoop.item.PrefAndSimilarityColumnWritable;
import org.apache.mahout.cf.taste.hadoop.item.VectorAndPrefsWritable;
import org.apache.mahout.cf.taste.hadoop.item.VectorOrPrefWritable;
import org.apache.mahout.math.VarIntWritable;
import org.apache.mahout.math.VectorWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class App 
{
    public static void main( String[] args ) throws IOException, InterruptedException, ClassNotFoundException
    {
        Logger logger = LoggerFactory.getLogger(App.class);

        HDFSUtil.cleanupTemporaryPath();
        HDFSUtil.cleanupOutputPath();

        Path inputPath = HDFSUtil.getInputPath();
        Path outputPath = HDFSUtil.getOutputPath();

        Path userVectors = new Path(HDFSUtil.getTemporaryPath(), "userVectors");
        Path itemVectors = new Path(HDFSUtil.getTemporaryPath(), "itemVectors");
        Path normedVectors = new Path(HDFSUtil.getTemporaryPath(), "normedVectors");
        Path partialDots = new Path(HDFSUtil.getTemporaryPath(), "partialDots");
        Path similarityMatrix = new Path(HDFSUtil.getTemporaryPath(), "similarityMatrix");

        Path recommendPrepUsers = new Path(HDFSUtil.getTemporaryPath(), "recommendPrepUsers");
        Path recommendPrepItems = new Path(HDFSUtil.getTemporaryPath(), "recommendPrepItems");

        Path recommendCombinedMapResults = new Path(recommendPrepItems + "," + recommendPrepUsers);

        Path recommendPrepPairs = new Path(HDFSUtil.getTemporaryPath(), "recommendPrepPairs");

        //Configuration

        Configuration config = new Configuration();
        config.set("similarity","CosineSimilarity");

        //Map input to user vectors
        Job userVectorJob = HadoopUtil.buildJob(
                inputPath,
                userVectors,
                TextInputFormat.class,
                SequenceFileOutputFormat.class,
                ToUserVectorMapper.class,
                VarIntWritable.class,
                VectorWritable.class,
                ToUserVectorReducer.class,
                VarIntWritable.class,
                VectorWritable.class,
                config);

        boolean success = userVectorJob.waitForCompletion(true);

        if(!success) {
            logger.error("UserVectorJob failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            return;
        }

        //Map userVectors to itemVectors
        Job itemVectorJob = HadoopUtil.buildJob(
                userVectors,
                itemVectors,
                ToItemVectorMapper.class,
                VarIntWritable.class,
                VectorWritable.class,
                ToItemVectorReducer.class,
                VarIntWritable.class,
                VectorWritable.class,
                config);

        success = itemVectorJob.waitForCompletion(true);

        if(!success) {
            logger.error("ItemVectorJob failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            return;
        }

        Job normsJob = HadoopUtil.buildJob(
                itemVectors,
                normedVectors,
                VectorNormMapper.class,
                VarIntWritable.class,
                VectorWritable.class,
                VectorNormMergeReducer.class,
                VarIntWritable.class,
                VectorWritable.class,
                config
        );

        normsJob.setCombinerClass(MergeVectorsCombiner.class);

        success = normsJob.waitForCompletion(true);

        if(!success) {
            logger.error("VectorNormsJob failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            return;
        }

        Job partialDotJob = HadoopUtil.buildJob(
                normedVectors,
                partialDots,
                PartialDotMapper.class,
                VarIntWritable.class,
                VectorWritable.class,
                ItemSimilarityReducer.class,
                VarIntWritable.class,
                VectorWritable.class,
                config);

        partialDotJob.setCombinerClass(PartialDotSumCombiner.class);

        success = partialDotJob.waitForCompletion(true);

        if(!success) {
            logger.error("PartialDotJob failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();

            throw new IllegalStateException();
        }

        Job symSimilarityMatrixJob = HadoopUtil.buildJob(
                partialDots,
                similarityMatrix,
                SimilarityMatrixMapper.class,
                VarIntWritable.class,
                VectorWritable.class,
                MergeVectorsCombiner.class,
                VarIntWritable.class,
                VectorWritable.class,
                config);

        success = symSimilarityMatrixJob.waitForCompletion(true);

        if(!success) {
            logger.error("SymSimilarityMatrixJob failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            throw new IllegalStateException();
        }

        Job getItemRowsJob = HadoopUtil.buildJob(
                similarityMatrix,
                recommendPrepItems,
                ItemSimilarityToRowMapper.class,
                VarIntWritable.class,
                VectorOrPrefWritable.class,
                Reducer.class,
                VarIntWritable.class,
                VectorOrPrefWritable.class,
                new Configuration()
        );

        success = getItemRowsJob.waitForCompletion(true);

        if(!success) {
            logger.error("getItemRowsJob failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            throw new IllegalStateException();
        }

        Job getUserPrefsJob = HadoopUtil.buildJob(
                userVectors,
                recommendPrepUsers,
                UserPreferenceToRowMapper.class,
                VarIntWritable.class,
                VectorOrPrefWritable.class,
                Reducer.class,
                VarIntWritable.class,
                VectorOrPrefWritable.class,
                new Configuration()
        );

        success = getUserPrefsJob.waitForCompletion(true);

        if(!success) {
            logger.error("getUserPrefsJob failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            throw new IllegalStateException();
        }

        Job pairItemsAndPrefs = HadoopUtil.buildJob(
                recommendCombinedMapResults,
                recommendPrepPairs,
                Mapper.class,
                VarIntWritable.class,
                VectorOrPrefWritable.class,
                RecommendationPreperationReducer.class,
                VarIntWritable.class,
                VectorAndPrefsWritable.class,
                new Configuration()
        );

        success = pairItemsAndPrefs.waitForCompletion(true);

        if(!success) {
            logger.error("pairItemsAndPrefs failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            throw new IllegalStateException();
        }

        Job recommendItems = HadoopUtil.buildJob(
                recommendPrepPairs,
                outputPath,
                PrepareRecommendationMapper.class,
                VarIntWritable.class,
                PrefAndSimilarityColumnWritable.class,
                RecommendationReducer.class,
                VarIntWritable.class,
                VectorOrPrefWritable.class,
                new Configuration()
        );

        success = recommendItems.waitForCompletion(true);

        if(!success) {
            logger.error("Recommendation failed. Aborting.");
            logger.info("Cleaning up temporary files.");
            HDFSUtil.cleanupTemporaryPath();
            throw new IllegalStateException();
        }
    }
}
