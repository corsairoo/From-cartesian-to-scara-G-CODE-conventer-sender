package g.code_conventer;


import com.fazecast.jSerialComm.SerialPort;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *Set of function to transform cartesian to scara and send to arduino
 * 
 * @author Karol Robak
 */
public class GCODE_Conventer {

    private static boolean started=false;
    private static final String src="C:\\Users\\Karol\\Desktop\\xd2.txt";
    private static final String out="C:\\Users\\Karol\\Desktop\\test2.txt";
    
    private static final String SERIAL_PORT="COM3";
    
    private static final char L='L';//long axis name
    private static final char S='S';//short axis name

    private static final long Lr=100; //long arm length 
    private static final long Sr=100;//short arm length
    private static final long R=200;
    private static final long r=40;

    private static final int MOTOR_STEPS_PRER_ROTATION=200;    //200 steps in 1 step mode, 400 in 1/2 step mode...

    private static final double ARM_LONG_STEPS_PER_ROTATION=/*MOTOR_STEPS_PRER_ROTATION;*/35D/20D;              //1.75
    private static final double ARM_SHORT_DEGREES_BY_ROTATION=/*MOTOR_STEPS_PRER_ROTATION;*/(116D/25D);         //116x30x25
    private static final double ARM_SHORT_ADDITIONAL_ROTATION=/*MOTOR_STEPS_PRER_ROTATION;*/(30D/25D);         //116x30x25

    private static final int RAPID_SPEED=300;

    
    private static enum REL_STATE{
        G90,    //absolute
        G91     //relative
    }
    private static REL_STATE STATE=REL_STATE.G90;
    private static boolean rapid=false;
    private static double speedrate=1.0;
    private static long [] position = {/*-R*/0,0,0};
    private static double [] angles = {/*DEGREE_BIG_ARM*//*-9*/0,-180/*DEGREE_SMALL_ARM/2*/};
    private static Object lock=new Object();
    private static Thread t;
    
    public static void main(String[] args) throws FileNotFoundException, IOException, InterruptedException {
     
    makeGCodeFile();
        System.out.println("spr "+ARM_LONG_STEPS_PER_ROTATION+" "+ARM_SHORT_DEGREES_BY_ROTATION);
   // sendGcode();
    }
    
    /**
     * @throws InterruptedException
     * @throws IOException 
     */
    private static void sendGcode()throws  InterruptedException, IOException{

            
           SerialPort port=SerialPort.getCommPort(SERIAL_PORT);
           port.setComPortParameters(9600, 8, 1, 0);
           port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
            System.out.println("Opened: "+ port.openPort());
           Thread.sleep(100);

           InputStream is=port.getInputStream();
          while(!port.isOpen()){
          Thread.sleep(500);
          }
          System.out.println("OPEN");
           
           BufferedInputStream bins=new BufferedInputStream(is);
  
           PrintWriter pw=new PrintWriter(port.getOutputStream());
            File fin=new File(out);
                
          Thread.sleep(500);
          String st;
           byte[] bf = new byte[1024];
          do{
           pw.write("START");
           pw.flush();  
           Thread.sleep(1000);
           bins.read(bf);
           st=new String(bf);
         
           System.out.println("OK-"+st);
          }while (st.compareTo("OK")==0);
          
          
              try (  FileReader fr = new FileReader(fin);BufferedReader br = new BufferedReader(fr);){
                       

                 t=new Thread(){
                            @Override
                            public void run() {
                               try {
                             String line;
                               int i=0;  
                           
                            while((line=br.readLine())!=null){
                             
                                pw.write(line);
                                pw.flush();  
                                byte[] buffer = new byte[1024];
                                 bins.read(buffer);
                                System.out.println(""+(++i)+ " "+line);
                                sleep(1000);
                          
                                int sec=0;
                                while ( (bins.read(buffer)) <= -1/*||!"OK".equals(got)*/ )
                                { 
                                    System.out.println((++sec*0.5/*+"   got "+got*/));
                                  sleep(500);
                                }
       
                                
                            }     } catch (IOException ex) {
                            Logger.getLogger(GCODE_Conventer.class.getName()).log(Level.SEVERE, null, ex);
                        }       catch (InterruptedException ex) {
                                    Logger.getLogger(GCODE_Conventer.class.getName()).log(Level.SEVERE, null, ex);
                                }
                            }
                            
                        };
                      t.start();
                      t.join();
               
            } catch (FileNotFoundException ex) { 
            Logger.getLogger(GCODE_Conventer.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(GCODE_Conventer.class.getName()).log(Level.SEVERE, null, ex);
        }finally{
                  bins.close();
                  is.close();
                  pw.close();
                      port.closePort();
              }    
         
    }
 
    /**
     * Do what name says
     * @throws IOException 
     */
    private static void makeGCodeFile() throws IOException{
     
        File fin=new File(src);
        File fout=new File(out);
        if(!fout.exists())
            fout.createNewFile();
       
        try (  FileWriter fw = new FileWriter(fout)) {
            FileReader fr = new FileReader(fin); 
            BufferedReader br= new BufferedReader(fr);
            
            String line;
            double maxSpeed=0.0;            
            boolean firstline=true;
          
            while ((line = br.readLine()) != null){
                if(firstline){
                  br.mark(0);
                  firstline=false;
                }
                    
                int nr=line.indexOf('F');
                if(nr!=-1){
                    int end=line.indexOf(' ', nr);
                    double newSpeed;
                    if(end!=-1)
                        newSpeed=Double.parseDouble(line.substring(nr+1,end));
                    else
                         newSpeed=Double.parseDouble(line.substring(nr+1));
                    if(newSpeed>maxSpeed)
                        maxSpeed=newSpeed;
                }
             } 
            fr = new FileReader(fin); 
            br= new BufferedReader(fr);
            
            
            if(RAPID_SPEED<maxSpeed)
                speedrate=RAPID_SPEED/maxSpeed;

            while ((line = br.readLine()) != null){

                String [] comands=line
                        .split(";");
                for(String one :comands){
                    String[] comand=one.split(" ");
                    fw.write(translate(comand));
                }
            }
            br.close();
            fw.flush();
        }
    }
    
    /**
     * Takes raw code and change positioning
     * @param comand comand, every row is option of comand
     * @return translated to scara comand line
     */
    private static String translate(String comand[]){
         String ret="";
         switch(comand[0]){
                   case "G00":
                   case "G0"://full speed 
                       rapid=true;
                       ret=(calculate(comand));
                   break;   
                   case "G01":
                   case "G1"://axis with federate
                       rapid=false;
                      ret=(calculate(comand));   
                   break;      
                   case "G90":
                       STATE=REL_STATE.G90;
                   break;    
                   case "G91":
                       STATE=REL_STATE.G91;
                   break;    
                   default:
                     
               
                   break;
               
               }
        return ret;
    }
    /**
     * Reads params from array and sends to Transition
     * @param code array of comands
     * @return calculated scara 
     */
    private static String calculate(String code[]){
         String ret="";
         double x=-1;
         double y=-1;
         double f=-1;
         double z=-1;

         
         if(code[0]=="G00"||code[0]=="G0"||code[0]=="G01"||code[0]=="G1")
             ret+=code[0]+" ";
         boolean have=false;
         for(String c : code){
             switch(c.charAt(0)){
                  case 'X':
                      have=true;
                      x=Double.parseDouble(c.substring(1));
                      break;
                  case 'Y':
                      have=true;
                      y=Double.parseDouble(c.substring(1));
                      break;
                  case 'Z':
                      have=true;
                      z=Double.parseDouble(c.substring(1));
                      break;
                  case 'F':    
                      f=Double.parseDouble(c.substring(1));
                      break;
                  default:
             }
         
         }
         if(x!=-1||y!=-1||z!=-1)
             ret+=Transition(x, y, z, f);
      
        if(have)
            return ret;
        return "";
    }
    /**
     * All magic happens here
     * @return 
     */
    private static String Transition(double xMove,double yMove,double zMove,double speed){
        String comand="";
        double xm=xMove;
        double ym=yMove;
        if(STATE==REL_STATE.G91){  //if relative then change to absolute
            xm=position[0]+xMove;
            ym=position[1]+yMove;
            
        }
     
        if(xMove==0&&xm==0)
            xm=position[0];
        if(yMove==0&&ym==0)
            ym=position[1];
        double newRaius=Math.hypot(xm, ym);
        if(newRaius>R)
            System.err.println("Object is outside workspace R<"+ newRaius);
        double alpha;
        double beta;
     
        double gamma=Math.atan2(ym, xm);//absolute degree angle to R
        beta=Math.acos(((xm*xm)+(ym*ym)-(Lr*Lr)-(Sr*Sr))/(-2*Lr*Sr));//between  arms 
        alpha=Math.asin((Lr*Math.sin(Math.PI-beta))/newRaius);   //between first arm and R
     
        double alphaAdd=Math.toDegrees(gamma+alpha);
     
        double betaAdd=Math.toDegrees(beta); 
        
           System.out.println("aA "+alphaAdd+" ang "+angles[0]);
       
       double betaChange=
               180-betaAdd;                                             ///what if betaAdd is less than 0
             
        if(Double.isNaN(alphaAdd))
             alphaAdd=0;
         if(Double.isNaN(betaChange))
             betaChange=0;

        System.out.println("NewADegree "+(alphaAdd-angles[0])); 
         
        double alphaNow=((alphaAdd-angles[0])*ARM_LONG_STEPS_PER_ROTATION)*MOTOR_STEPS_PRER_ROTATION/360;
     
         System.out.println("NewASteps "+alphaNow); 
    
         
        double betaNow=((betaChange-angles[1])*ARM_SHORT_DEGREES_BY_ROTATION)*MOTOR_STEPS_PRER_ROTATION/360+((alphaAdd-angles[0])*ARM_SHORT_ADDITIONAL_ROTATION)*MOTOR_STEPS_PRER_ROTATION/360;//drugi człon do poprawy
       
         position[0]=(long) xm;
        position[1]=(long) ym;
        
        if((betaNow<0&&(betaChange-angles[1])>0||(betaNow>0&&(betaChange-angles[1])<0))){
            betaNow*=-1;
        
        }
           
        comand+=""+L+((long)alphaNow)+
                " "+S+((long)betaNow);
       
        if((!Double.isNaN(alphaAdd))&&alphaAdd!=0)
             angles[0]=angles[0]+(alphaAdd-angles[0]);
        if((!Double.isNaN(betaChange))&&betaChange!=0)
            angles[1]=  //betaAdd-angles[1];
            angles[1]+(betaChange-angles[1]);
        
      
        if(zMove!=0){
            comand+=" Z"+(zMove*MOTOR_STEPS_PRER_ROTATION);
        }
        if(rapid)
            comand+=" F"+RAPID_SPEED;
        else if(speed!=-1)
            comand+=" F"+(speed*speedrate);
            
        comand+="\n";
        
        return comand;
    }
   
}
