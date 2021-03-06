/*
 * chombo: Hadoop Map Reduce utility
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.chombo.mr;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.chombo.util.SecondarySort;
import org.chombo.util.Tuple;
import org.chombo.util.Utility;

/**
 * Converts time sequence to time gap sequence
 * @author pranab
 *
 */
public class TimeGapSequenceGenerator extends Configured implements Tool {
	//private static String configDelim = ",";

	@Override
	public int run(String[] args) throws Exception {
        Job job = new Job(getConf());
        String jobName = "Time sequence to time gap sequence conversion";
        job.setJobName(jobName);
        
        job.setJarByClass(TimeGapSequenceGenerator.class);

        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        Utility.setConfiguration(job.getConfiguration(), "chombo", true);
        job.setMapperClass(TimeGapSequenceGenerator.TimeGapMapper.class);
        job.setReducerClass(TimeGapSequenceGenerator.TimeGapReducer.class);
        
        job.setMapOutputKeyClass(Tuple.class);
        job.setMapOutputValueClass(Tuple.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        job.setGroupingComparatorClass(SecondarySort.TuplePairGroupComprator.class);
        job.setPartitionerClass(SecondarySort.TuplePairPartitioner.class);
        
        int numReducer = job.getConfiguration().getInt("tgs.num.reducer", -1);
        numReducer = -1 == numReducer ? job.getConfiguration().getInt("num.reducer", 1) : numReducer;
        job.setNumReduceTasks(numReducer);

        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
	}

	/**
	 * @author pranab
	 *
	 */
	public static class TimeGapMapper extends Mapper<LongWritable, Text, Tuple, Tuple> {
		private Tuple outKey = new Tuple();
		private Tuple outVal = new Tuple();
		private int[]  attributes;
        private String fieldDelimRegex;
        private String[] items;
        private int[] idOrdinals;
        private int timeStampFieldOrdinal;
        private long timeStamp;
        private boolean isEpochTime;
        private SimpleDateFormat dateFormat;
        private int timeZoneShiftHours;
        private boolean includeRawDateTimeField;
        
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelimRegex = Utility.getFieldDelimiter(config, "tgs.field.delim.regex", "field.delim.regex", ",");
        	attributes = Utility.intArrayFromString(config.get("tgs.quant.attr.list"), Utility.configDelim);
        	idOrdinals = Utility.intArrayFromString(config.get("tgs.id.field.ordinals"), Utility.configDelim);
        	timeStampFieldOrdinal = Utility.assertIntConfigParam(config,"tgs.time.stamp.field.ordinal", "missing timestamp field ordinal");
        
        	isEpochTime = config.getBoolean("tgs.is.epoch.time", false);
        	if (!isEpochTime) {
                String dateFormatStr = config.get("tgs.date.format.str",  "yyyy-MM-dd HH:mm:ss");
                dateFormat = new SimpleDateFormat(dateFormatStr);
                timeZoneShiftHours = config.getInt("tgs.time.zone.shift.hours", 0);
        	}
        	includeRawDateTimeField = config.getBoolean("tgs.include.raw.date.time.field", false);
        } 
        
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#map(KEYIN, VALUEIN, org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void map(LongWritable key, Text value, Context context)
        		throws IOException, InterruptedException {
        	items  =  value.toString().split(fieldDelimRegex, -1);
        	try {
				timeStamp = Utility.getEpochTime(items[timeStampFieldOrdinal], isEpochTime, dateFormat, timeZoneShiftHours);
				
            	outKey.initialize();
            	outVal.initialize();
        		outKey.addFromArray(items, idOrdinals);
        		outKey.append(timeStamp);

        		outVal.add(timeStamp);
        		if (includeRawDateTimeField) {
        			outVal.append(items[timeStampFieldOrdinal]);
        		}
        		
        		if (null != attributes) {
        			outVal.addFromArray(items, attributes);
        		}

            	context.write(outKey, outVal);
			} catch (ParseException ex) {
				throw new IOException("parsing error with date time field", ex);
			}
        }
	}
	
	/**
	* @author pranab
	*
	*/
	public static class  TimeGapReducer extends Reducer<Tuple, Tuple, NullWritable, Text> {
		protected Text outVal = new Text();
		protected StringBuilder stBld =  new StringBuilder();;
		protected String fieldDelim;
		private String timeGapUnit;
		private int numIDFields;
		private int numAttributes;
		private long timeGap;
		
		/* (non-Javadoc)
		 * @see org.apache.hadoop.mapreduce.Reducer#setup(org.apache.hadoop.mapreduce.Reducer.Context)
		 */
		protected void setup(Context context) throws IOException, InterruptedException {
			Configuration config = context.getConfiguration();
			fieldDelim = config.get("field.delim.out", ",");
        	timeGapUnit = config.get("tgs.time.gap.unit");
        	numIDFields = Utility.intArrayFromString(config.get("tgs.id.field.ordinals"), Utility.configDelim).length;
        	int[] attributes = Utility.intArrayFromString(config.get("tgs.quant.attr.list"), Utility.configDelim);
        	numAttributes = null != attributes ? attributes.length : 0;
		}

        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
         */
        protected void reduce(Tuple  key, Iterable<Tuple> values, Context context)
        		throws IOException, InterruptedException {
    		long lastTimeStamp = -1;
    		for (Tuple val : values) {
    			if (lastTimeStamp > 0) {
            		stBld.delete(0, stBld.length());
            		for (int i = 0; i < numIDFields; ++i) {
            			stBld.append(key.getString(i)).append(fieldDelim);
            		}

            		timeGap = val.getLong(0) - lastTimeStamp;
    				if (timeGapUnit.equals("hour")) {
    					timeGap /= Utility.MILISEC_PER_HOUR;
    				} else if (timeGapUnit.equals("day")) {
    					timeGap /= Utility.MILISEC_PER_DAY;
    				}
    				stBld.append(timeGap).append(fieldDelim);
            		for (int i = 0; i < numAttributes; ++i) {
            			stBld.append(val.getString(i+1)).append(fieldDelim);
            		}
        			stBld.deleteCharAt(stBld.length() -1);
                	outVal.set(stBld.toString());
        			context.write(NullWritable.get(), outVal);
    			}
    			lastTimeStamp = val.getLong(0);
    			
    		}
        }
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		int exitCode = ToolRunner.run(new TimeGapSequenceGenerator(), args);
		System.exit(exitCode);
	}
	
}
