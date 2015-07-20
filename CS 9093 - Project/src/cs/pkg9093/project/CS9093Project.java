package cs.pkg9093.project;

import java.io.*;
import java.util.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

import edu.cmu.sphinx.frontend.util.AudioFileDataSource;
import edu.cmu.sphinx.linguist.language.grammar.NoSkipGrammar;
import edu.cmu.sphinx.recognizer.Recognizer;
import edu.cmu.sphinx.result.Result;
import edu.cmu.sphinx.util.props.ConfigurationManager;
import java.text.SimpleDateFormat;

public class CS9093Project {

    public static void main(String[] args) throws UnsupportedAudioFileException, IOException 
    {       
        Window win = new Window();
    } 
}

class VoiceExtraction
{
    AudioFormat optimalFormat;
    int optimalTime;
    
    String dir, phrase;
    String[] keywords;
    ArrayList<String> dirs;

    int[][] results;
    int padding;    
    
    AudioFileFormat aff;
    AudioFormat af;     
    
    VoiceExtraction(String str1, String str2) throws UnsupportedAudioFileException, IOException
    {     
        optimalFormat = new AudioFormat((float)16000, 16, 1, true, false);
        optimalTime = 180;
        
        dir = str1;
        phrase = str2;
        
        keywords = phrase.split(" ");
        
        results = new int[keywords.length][3];
        
        //get .wave files only from the provided directory
        dirs = new ArrayList<String>();
        
        File directory = new File(dir);
        File[] files = directory.listFiles();
        
        fileProcessing(files);
                
        //get audio format from the 1st voice sample
        aff = AudioSystem.getAudioFileFormat(new File(dirs.get(0)));
        af = aff.getFormat();   
        padding = (int)(optimalFormat.getFrameRate()/20) * optimalFormat.getFrameSize();
    }
    
    private void fileProcessing(File[] files) throws UnsupportedAudioFileException, IOException
    {     
        for(int i=0; i < files.length; i++)
        {
            String temp = files[i].toString();
            if(temp.substring(temp.length() - 4, temp.length()).equals(".wav"))
            {
                //convert the input file to the optimal format: 16000 Hz, mono, 2 bytes/frame
                AudioInputStream stream = convertStream(AudioSystem.getAudioInputStream(files[i]), optimalFormat);
                
                //check the length of the file
                //if longer than optimalTime (3 min), split it into optimalTime sections
                //if not, just create a new file to run KWS on
                int time = (int)(AudioSystem.getAudioFileFormat(files[i]).getFrameLength()/AudioSystem.getAudioFileFormat(files[i]).getFormat().getSampleRate());

                String fileName = dir + "temp_" + i + "_" + getCurrentDateTime() + ".wav";
                File outFile = new File(fileName);
                outFile.createNewFile();

                //write out to the file
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, outFile);
                
                if(time <= optimalTime)
                {
                    System.out.println("not splitting");   
                    
                    //add to dirs array to pass in KWS later
                    dirs.add(fileName);
                }
                else
                {                    
                    System.out.println("splitting");
                    AudioInputStream bigStream = AudioSystem.getAudioInputStream(outFile);
                    AudioInputStream[] streams = splitStream(bigStream, optimalTime);
                    for(int j=0; j < streams.length; j++)
                    {
                        fileName = dir + "temp_" + i + "_" + j + "_" + getCurrentDateTime() + ".wav";
                        File smallerFile = new File(fileName);
                        outFile.createNewFile();

                        //write out to the file
                        AudioSystem.write(streams[j], AudioFileFormat.Type.WAVE, smallerFile);
                    
                        //add to dirs array to pass in KWS later
                        dirs.add(fileName);
                        
                        streams[j].close();                        
                    }
                    bigStream.close();
                    outFile.delete();                    
                }                
                stream.close();
            }
        } 
    }
    
    //get the file with numOfBytes and skipBytes for each keyword
    void analyze() throws MalformedURLException, UnsupportedAudioFileException, IOException
    {
        for(int i=0; i < keywords.length; i++) //loop through each keyword
        {
            for(int j=0; j < dirs.size(); j++) //loop through each sample to find keyword
            {
                System.out.println("Running KWS on " + dirs.get(j));
                String temp = KWS("file:" + dirs.get(j), keywords[i]);
                
                if(temp.indexOf(keywords[i]) != -1)
                {
                    double[] times = findTimeStamp(temp, keywords[i]);
                    
                    double duration = times[1] - times[0];
                    
                    double numOfFrames = duration * af.getSampleRate();
                    double numOfBytes = numOfFrames * af.getFrameSize();
                    
                    double skipBytes = times[0] * af.getSampleRate() * af.getFrameSize();
                    
                    //for debugging purpose
                    System.out.println(temp);
                    System.out.println("File: " + dirs.get(j));
                    System.out.println("word: " + keywords[i]);
                    System.out.println("Start: " + times[0]);
                    System.out.println("End: " + times[1]);
                    System.out.println("Bytes: " + numOfBytes);
                    System.out.println("Skip: " + skipBytes);
                    
                    results[i][0] = j;
                    results[i][1] = (int)numOfBytes;
                    results[i][2] = (int)skipBytes;
                    
                    //if the keyword is found in this voice sample, stop looking in other samples
                    j = dirs.size();
                }
            }
        }
    }
    
    //get the bytes needed for each keyword and put them together into one .wav file
    void extract() throws UnsupportedAudioFileException, IOException
    {        
        AudioInputStream[] streams = new AudioInputStream[results.length];
        //get the bytes for each keyword and put them into an AudioInputStream
        for(int i=0; i < results.length; i++)
        {
            AudioInputStream stream = AudioSystem.getAudioInputStream(new File(dirs.get(results[i][0])));

            if(results[i][2] > padding)
            {
                stream.skip(results[i][2] - padding);
            }

            byte[] temp;

            if(stream.getFrameLength() >= (results[i][1] + (2*padding)))
            {
                temp = new byte[results[i][1] + (2* padding)];
                stream.read(temp, 0, results[i][1] + 2 * padding);
            }
            else
            {
                temp = new byte[(int)stream.getFrameLength()];
                stream.read(temp, 0, (int)stream.getFrameLength());
            }

            ByteArrayInputStream temp_bais = new ByteArrayInputStream(temp);

            streams[i] = new AudioInputStream(temp_bais, af, temp.length/af.getFrameSize());

            stream.close();
        }

        AudioInputStream output = mergeStreams(streams);
        
        //close all the opened stream in the array streams
        for(int i=0; i < streams.length; i++)
        {
            streams[i].close();
        }
        
        //create a new file with current date and time as the filename
        String fileName = getCurrentDateTime();        
        File result = new File(dir + "result_" + fileName + ".wav");
        result.createNewFile();

        //write out to the file
        AudioSystem.write(output, aff.getType(), result);
        output.close();
        
        //delete all the created file
        deleteFiles(dirs);
        
        //display un-find words
        displayUnfindWord();
    }
    
    //KWS from Sphinx-4 SDK
    String KWS(String url, String kw) throws MalformedURLException
    {
        ConfigurationManager cm = new ConfigurationManager("./src/config.xml");
        Recognizer recognizer = (Recognizer) cm.lookup("recognizer");
        NoSkipGrammar grammar = (NoSkipGrammar) cm.lookup("NoSkipGrammar");

        grammar.addKeyword(kw);
        
        AudioFileDataSource dataSource = (AudioFileDataSource) cm.lookup("audioFileDataSource");
        dataSource.setAudioFile(new URL(url),null);
        recognizer.allocate();
        Result result = recognizer.recognize();
        
        recognizer.deallocate();
        
        return result.getTimedBestResult(false, true);        
    }
    
    //find start and end time for the keyword in the string from KWS
    double[] findTimeStamp(String str, String kw)
    {
        double timeStamp[] = new double[2];

        //get the first occurence of the keyword
        int startIndex = str.indexOf(kw) + kw.length() + 1;
        str = str.substring(startIndex);
        str = str.substring(0, str.indexOf(")"));

        //split into start and end time
        String startTime = str.substring(0,str.length()/2);
        String endTime;
        if(startTime.charAt(startTime.length()-1)==',')
        {
            startTime = startTime.substring(0, startTime.length()-1);
            endTime = str.substring(startTime.length()+1);
        }
        else
        {
            endTime = str.substring(startTime.length()+1);
        }

        timeStamp[0] = Double.parseDouble(startTime);
        timeStamp[1] = Double.parseDouble(endTime);
            
        return timeStamp;
    }
    
    //merge each AudioInputStream together, one by one    
    AudioInputStream mergeStreams(AudioInputStream[] streams)
    {
        AudioInputStream result = null;
        
        if(streams.length == 1)
        {
            result = streams[0];
        }
        else if(streams.length >= 2)
        {
            result = new AudioInputStream(
                     new SequenceInputStream(streams[0], streams[1]), af, 
                     streams[0].getFrameLength() + streams[1].getFrameLength());
            
            for(int i=2; i < streams.length; i++)
            {
                result = new AudioInputStream(
                         new SequenceInputStream(result, streams[i]), af,
                         result.getFrameLength() + streams[i].getFrameLength());
            }
        }
        
        return result;
    }
    
    String getCurrentDateTime()
    {
        Calendar currentTime = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy_hh-mm");
        return sdf.format(currentTime.getTime());
    }
    
    //convert the input AudioInputStream into the given AudioFormat
    //if cannot, then just return the original stream
    AudioInputStream convertStream(AudioInputStream in, AudioFormat format)
    {        
       if(AudioSystem.isConversionSupported(format, in.getFormat()))
       {
           return AudioSystem.getAudioInputStream(format, in);
       }
       else
       {
            return in;
       }
    }
    
    AudioInputStream[] splitStream(AudioInputStream in, int thres) throws IOException
    {
        int size;
        
        int numOfBytes = (int)(thres * in.getFormat().getSampleRate() * in.getFormat().getFrameSize());
        
        int time = (int)(in.getFrameLength() / in.getFormat().getSampleRate());
        if((time%thres)==0)
        {
            size = time / thres;
        }
        else
        {
            size = (time / thres) + 1;
        }
        
        AudioInputStream[] outs = new AudioInputStream[size];
        
        for(int i=0; i < size; i++)
        {
            byte[] temp = new byte[numOfBytes];

            in.read(temp);
           
            ByteArrayInputStream bais = new ByteArrayInputStream(temp);
            outs[i] = new AudioInputStream(bais, in.getFormat(), temp.length/in.getFormat().getFrameSize());
        }
        return outs;
    }
    
    void deleteFiles(ArrayList<String> files)
    {
        for(int i=0; i < files.size(); i++)
        {
            File file = new File(files.get(i));
            file.delete();
        }
    }
    
    void displayUnfindWord()
    {
        String words = "";
        for(int i=0; i < results.length; i++)
        {
            if(results[i][0] == 0 && results[i][1] == 0 && results[i][2] == 0)
            {
                words += keywords[i] + " ";
            }
        }
        
        if(!words.equals(""))
        {
            new ErrWindow("Cannot find words: " + words);
        }
    }
}

class Window extends JFrame
{
    JLabel dirLabel, phraseLabel;
    JTextField dirField;
    JTextArea phraseArea;
    JButton browseButton, runButton;
    JScrollPane display;
    
    JPanel jp;
    
    Window()
    {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4,4,4,4);
        
        dirLabel = new JLabel("Directory: ");
        phraseLabel = new JLabel("Phrase (only word and whitespace): ");
        
        dirField = new JTextField(20);
        
        phraseArea = new JTextArea(3,29);
        phraseArea.setLineWrap(true);
        phraseArea.setWrapStyleWord(true);
        
        display = new JScrollPane(phraseArea);
        
        browseButton = new JButton("Browser");
        browseButton.addActionListener(new PressListener());
        runButton = new JButton("Run");
        runButton.addActionListener(new PressListener());
        
        jp = new JPanel(new GridBagLayout());
        
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.LINE_START;
        jp.add(dirLabel, c);
        
        c.gridx = 0;
        c.gridy = 1;
        c.anchor = GridBagConstraints.CENTER;
        jp.add(dirField, c);
        
        c.gridx = 1;
        c.gridy = 1;
        c.anchor = GridBagConstraints.LINE_START;
        jp.add(browseButton, c);
        
        c.gridx = 0;
        c.gridy = 2;
        c.anchor = GridBagConstraints.LINE_START;
        jp.add(phraseLabel, c);
        
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        jp.add(display, c);
        
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        jp.add(runButton, c);
        
        setSize(350,220);
        setVisible(true);
        setResizable(false);
        setTitle("Voice Extraction");
        setLayout(new GridBagLayout());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        c.gridx = 0;
        c.gridy = 0;
        add(jp, c);
    }
    
    class PressListener implements ActionListener
    {
        @Override
        public void actionPerformed(ActionEvent e)
        {
            if(e.getSource() == runButton)
            {
                if(dirField.getText().equals(""))
                {
                    new ErrWindow("Please select directory");
                }
                else if(phraseArea.getText().equals(""))
                {
                    new ErrWindow("Please enter phrase");
                }
                else
                {                    
                    try {
                        VoiceExtraction ve = new VoiceExtraction(dirField.getText(), phraseArea.getText());
                        ve.analyze();
                        ve.extract();
                    } catch (IOException ex) {
                        Logger.getLogger(Window.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (UnsupportedAudioFileException ex) {
                        Logger.getLogger(Window.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
            else if(e.getSource() == browseButton)
            {
                JFileChooser dirChooser = new JFileChooser("./resource/wav/");
                dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int returnVal = dirChooser.showOpenDialog(jp);
                
                if(returnVal == JFileChooser.APPROVE_OPTION)
                {
                    dirField.setText(dirChooser.getSelectedFile().toString() + "/");
                }
            }
        }
    }
}

class ErrWindow extends JFrame
{
    private String msg;
    private JLabel errorMsg;
    private JPanel jp;
    
    ErrWindow(String str)
    {
        GridBagConstraints c = new GridBagConstraints();
        
        msg = str;
        
        errorMsg = new JLabel("Error: " + msg);
        
        jp = new JPanel(new GridBagLayout());
        
        c.gridx = 0;
        c.gridy = 0;
        jp.add(errorMsg, c);
        
        add(jp);
        
        setSize(300,100);
        setTitle("Error");
        setResizable(false);
        setVisible(true);

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }
}

