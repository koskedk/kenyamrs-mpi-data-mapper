/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.emr.utilities;

import com.almworks.sqlite4java.SQLite;
import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteStatement;
import com.opencsv.CSVReader;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.sql.Connection;
import java.util.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JOptionPane;

import org.apache.commons.lang3.StringUtils;

/**
 * Class to load and parse CSV files
 * @author LEONARD NDUATI
 */
public class CSVLoader {
    private static final 
		String SQL_INSERT = "INSERT INTO ${table}(${keys}) VALUES(${values})";
	private static final String TABLE_REGEX = "\\$\\{table\\}";
	private static final String KEYS_REGEX = "\\$\\{keys\\}";
	private static final String VALUES_REGEX = "\\$\\{values\\}";

	private Connection connection;
	private char seprator;
        SQLiteConnection db=null;
        /**
	 * Public constructor to build CSVLoader object with
	 * Connection details. The connection is closed on success
	 * or failure.
	 * @param connection {@link Connection} 
	 */
	public CSVLoader(Connection connection) {
		SQLite.setLibraryPath("lib");
		this.connection = connection;
		//Set default separator
		this.seprator = ',';
	}
        /**
	 * Parse CSV file using OpenCSV library and load in 
	 * given database table. 
	 * @param csvFile {@link String} Input CSV file
	 * @param tableName {@link String} Database table name to import data
	 * @param truncateBeforeLoad {@link boolean} Truncate the table before inserting 
	 * 			new records.
         * @param destinationColumns {@link String[]} Array containing the destination columns
	 */
        public void loadCSV(String csvFile, String tableName,boolean truncateBeforeLoad,String[] destinationColumns,List columnsToBeMapped) throws Exception {
            CSVReader csvReader = null;
            if(null == this.connection) {
		throw new Exception("Not a valid connection.");
            }
            try {
			
                csvReader = new CSVReader(new FileReader(csvFile), this.seprator);

            } catch (Exception e) {
		String stacktrace=org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e);
                JOptionPane.showMessageDialog(null, "Error occured while executing file. Error Details: " + stacktrace, "File Error", JOptionPane.ERROR_MESSAGE);
		throw new Exception("Error occured while executing file. "
					+ stacktrace);
            }
            String[] headerRow = csvReader.readNext();

            if (null == headerRow) {
		throw new FileNotFoundException(
					"No columns defined in given CSV file." +
					"Please check the CSV file format.");
            }
            //Get indices of columns to be mapped
            List mapColumnsIndices=new ArrayList();
            for(Object o: columnsToBeMapped){
                String column=(String)o;
                column=column.substring(column.lastIndexOf(".") + 1,column.length());
                int i;
                
                for(i=0;i<headerRow.length;i++){
                    
                    if(headerRow[i].equals(column)){
                        mapColumnsIndices.add(i);
                    }
                }
            }
            
            
            String questionmarks = StringUtils.repeat("?,", headerRow.length);
            questionmarks = (String) questionmarks.subSequence(0, questionmarks
				.length() - 1);

            String query = SQL_INSERT.replaceFirst(TABLE_REGEX, tableName);
            query = query.replaceFirst(KEYS_REGEX, StringUtils.join(destinationColumns, ","));
            query = query.replaceFirst(VALUES_REGEX, questionmarks);

            System.out.println("Query: " + query);
            String[] nextLine;
            Connection con = null;
            PreparedStatement ps = null;
            PreparedStatement ps2 = null;
            PreparedStatement reader=null;
            ResultSet rs = null;
            try{
                con = this.connection;
                con.setAutoCommit(false);
                ps = con.prepareStatement(query);
                
                File file=new File("sqlite/db");
                if(!file.exists()){
                    file.createNewFile();
                }
                db=new SQLiteConnection(file);
                db.open(true);
                
                //if destination table==person, also add an entry in the table person_identifier
                //get column indices for the person_id and uuid columns
                int person_id_column_index=-1;
                int uuid_column_index=-1;
                if(tableName.equals("person")){
                    int i;
                    ps2 = con.prepareStatement("insert into person_identifier(person_id,identifier_type_id,identifier) values(?,?,?)");
                    for(i=0;i<headerRow.length;i++){
                        if(headerRow[i].equals("person_id")){
                            person_id_column_index=i;
                        }
                        if(headerRow[i].equals("uuid")){
                            uuid_column_index=i;
                        }
                    }
                }
                
		if(truncateBeforeLoad) {
                    //delete data from table before loading csv
                    con.createStatement().execute("DELETE FROM " + tableName);
		}
                final int batchSize = 1000;
		int count = 0;
		Date date = null;
                
                while ((nextLine = csvReader.readNext()) != null) {
                    if (null != nextLine) {
                        int index = 1;
                        int person_id=-1;
                        String uuid="";
                        int identifier_type_id=3;
                        if(tableName.equals("person")){
                            reader = con.prepareStatement("select identifier_type_id from identifier_type where identifier_type_name='UUID'");
                            rs = reader.executeQuery();
                            while(rs.next()){
                                identifier_type_id=rs.getInt("identifier_type_id");
                            }
                        }
			for (String string : nextLine) {
                            //if current index is in the list of columns to be mapped, we apply that mapping
                            for(Object o: mapColumnsIndices){
                                int i=(int)o;
                                if(index==(i+1)){
                                    //apply mapping to this column
                                    string=applyDataMapping(string);
                                }
                            }
                            if(tableName.equals("person")){
                                //get person_id and uuid
                                
                                if(index==(person_id_column_index + 1)){
                                    person_id=Integer.parseInt(string);
                                }
                                
                                if(index==(uuid_column_index + 1)){
                                    uuid=string;
                                }
                                
                                
                            }
                            //check if string is a date
                            if(string.matches("\\d{2}-[a-zA-Z]{3}-\\d{4}")){
                                java.sql.Date dt=formatDate(string);
                                
                                ps.setDate(index++, dt);
                            }else{
                                if("".equals(string)){
                                    ps.setNull(index++, Types.NULL);
                                }else{
                                    ps.setString(index++, string);
                                }
                                
                            }
                            
			}
                        if(tableName.equals("person")){
                            if(!"".equals(uuid)  && person_id!=-1 ){
                                ps2.setInt(1, person_id);
                                ps2.setInt(2, identifier_type_id);
                                ps2.setString(3, uuid);
                                    
                                ps2.addBatch();
                            }
                        }
                        
                        ps.addBatch();
                    }
                    if (++count % batchSize == 0) {
			ps.executeBatch();
                        if(tableName.equals("person")){
                            ps2.executeBatch();
                        }
                    }
                 }
                 ps.executeBatch(); // insert remaining records
                 if(tableName.equals("person")){
                    ps2.executeBatch();
                 }
                 
                 con.commit();
            }catch (Exception e) {
                if(con!=null)
                    con.rollback();
                if(db!=null)
                    db.dispose();
		String stacktrace=org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace(e);
                JOptionPane.showMessageDialog(null, "Error occured while executing file. Error Details: " + stacktrace, "File Error", JOptionPane.ERROR_MESSAGE);
		throw new Exception("Error occured while executing file. "
					+ stacktrace);
            } finally {
                if(null!=reader)
                    reader.close();
		if (null != ps)
                    ps.close();
                if (null != ps2)
                    ps2.close();
		if (null != con)
                    con.close();

		csvReader.close();
            }
        }
        private String applyDataMapping(String oldString){
            String newString=null;
            SQLiteStatement st=null;
            try{
                st = db.prepare("select sourceValue,dataMapping from mappings");
                while (st.step()) {
                    String sourceVal=st.columnString(0);
                    String destVal=st.columnString(1);
                    
                    if(sourceVal.toLowerCase().equals(oldString.toLowerCase())){
                        newString=destVal;
                    }
                }
            }catch(Exception e){
                //fail silently
                return oldString;
            }finally{
                if(st!=null)
                    st.dispose();
            }
            return newString==null?oldString:newString;
        }
        /**
         * Method to format a String to a date
         * @param oldString {@link String} the string to be formatted
         * @return {@link java.sql.Date} The date object
         */
        private java.sql.Date formatDate(String oldString){
            java.sql.Date newDateString = null;
            try {
                final String OLD_FORMAT = "dd-MMM-yyyy";
                final String NEW_FORMAT = "dd/MM/yyyy HH:mm:ss";


                SimpleDateFormat sdf = new SimpleDateFormat(OLD_FORMAT);
                Date d = sdf.parse(oldString);
                sdf.applyPattern(NEW_FORMAT);
                newDateString = new java.sql.Date(d.getTime());  
            } catch (ParseException ex) {
                //Logger.getLogger(CSVLoader.class.getName()).log(Level.WARNING, null, ex);
                
            }
            return newDateString;
        }
        
	public char getSeprator() {
		return seprator;
	}

	public void setSeprator(char seprator) {
		this.seprator = seprator;
	}
}