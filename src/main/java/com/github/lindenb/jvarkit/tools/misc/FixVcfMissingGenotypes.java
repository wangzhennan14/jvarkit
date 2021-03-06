/*
The MIT License (MIT)

Copyright (c) 2014 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:
* 2014 creation

*/
package com.github.lindenb.jvarkit.tools.misc;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFormatHeaderLine;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLineType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.lindenb.jvarkit.io.IOUtils;
import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;
import com.github.lindenb.jvarkit.util.vcf.VCFUtils;
import com.github.lindenb.jvarkit.util.vcf.VcfIterator;

public class FixVcfMissingGenotypes extends AbstractFixVcfMissingGenotypes
	{
	private static final org.slf4j.Logger LOG = com.github.lindenb.jvarkit.util.log.Logging.getLog(FixVcfMissingGenotypes.class);
	
	public FixVcfMissingGenotypes()
		{
		
		}
	
	@Override
	protected Collection<Throwable> call(final String inputName) throws Exception {
		VcfIterator in=null;
		try {
			final Set<File> bamFiles=  IOUtils.unrollFileCollection(super.bamList);
			final Map<String,Set<File>> sample2bam=new HashMap<>(bamFiles.size());

			final SamReaderFactory srf=SamReaderFactory.makeDefault();
			srf.validationStringency(ValidationStringency.SILENT);
			
			for(final File bam: bamFiles)
				{
				LOG.info("Reading header for "+bam);
				final SamReader reader=srf.open(bam);
				final SAMFileHeader header=reader.getFileHeader();
				for(final SAMReadGroupRecord g:header.getReadGroups())
					{
					if(g.getSample()==null) continue;
					String sample=g.getSample();
					if(sample.isEmpty()) continue;
					Set<File> set= sample2bam.get(sample);
					if(set==null)
						{
						set=new HashSet<>();
						sample2bam.put(sample,set);
						}
					set.add(bam);
					}
				reader.close();
				}
			in =  openVcfIterator(inputName);
			
			final File tmpFile1 = File.createTempFile("fixvcf", ".vcf",getTmpDirectories().get(0));
			final File tmpFile2 = File.createTempFile("fixvcf", ".vcf",getTmpDirectories().get(0));
			VCFHeader header=in.getHeader();
			VCFHeader h2=new VCFHeader(header);
			final String FIXED_TAG="FXG";
			h2.addMetaDataLine(new VCFFormatHeaderLine(FIXED_TAG,1,VCFHeaderLineType.Integer,"Genotype was set as homozygous (min depth ="+this.minDepth+")"));
			super.addMetaData(h2);

			for(int i=0;i< header.getNGenotypeSamples();++i)
				{
				int countFixed=0;
				int countNonFixed=0;
				int countTotal=0;
				
				final String sample= header.getSampleNamesInOrder().get(i);
				LOG.info("Sample: "+sample);
				Set<File> bams = sample2bam.get(sample);
				if(bams==null) bams = new HashSet<File>();
				if(bams.isEmpty())
					{
					LOG.warn("No bam to fix sample "+sample);
					//don't 'continue' for simplicity
					}
				final List<SamReader> samReaders= new ArrayList<>(bams.size());
				for(File bam:bams)
					{
					LOG.info("Opening "+bam);
					samReaders.add(srf.open(bam));
					}
				final SAMSequenceDictionaryProgress progress = new SAMSequenceDictionaryProgress(header);
				final VariantContextWriter w = VCFUtils.createVariantContextWriter(i%2==0?tmpFile1:tmpFile2);	
				w.writeHeader(h2);
				while(in.hasNext())
					{
					final VariantContext ctx= progress.watch(in.next());
					countTotal++;
					if(samReaders.isEmpty()) 
						{
						w.add(ctx);
						continue;
						}
					final Genotype genotype = ctx.getGenotype(sample);
					if(genotype!=null && genotype.isCalled())
						{	
						w.add(ctx);
						continue;
						}
					int depth=0;
					//get depth for this position
					for(SamReader sr: samReaders)
						{
						SAMRecordIterator iter=sr.query(ctx.getContig(), ctx.getStart(), ctx.getEnd(), false);
						while(iter.hasNext())
							{
							final SAMRecord rec=iter.next();
							if(rec.getReadUnmappedFlag()) continue;
							if(rec.getDuplicateReadFlag()) continue;
							if(rec.isSecondaryOrSupplementary()) continue;
							if(rec.getMappingQuality()==0 ) continue;
							if(rec.getReadFailsVendorQualityCheckFlag()) continue;
							
							final SAMReadGroupRecord rg=rec.getReadGroup();
							if(!sample.equals(rg.getSample())) continue;
							final Cigar cigar=rec.getCigar();
							if(cigar==null) continue;
							int refPos=rec.getAlignmentStart();
							for(final CigarElement ce:cigar.getCigarElements())
								{
								if( refPos > ctx.getEnd() ) break;
								if(!ce.getOperator().consumesReferenceBases()) continue;
								if( ce.getOperator().consumesReadBases())
									{
									for(int n=0;n< ce.getLength();++n )
										{
										if( refPos+n < ctx.getStart() ) continue;
										if( refPos+n > ctx.getEnd()) break;
										depth++;
										}
									
									}
								refPos+= ce.getLength();
								}
							}
						iter.close();
						}
					depth /= ( 1 + ctx.getEnd() - ctx.getStart() );
					
					if(depth< this.minDepth)
						{	
						countNonFixed++;
						w.add(ctx);
						continue;
						}
					final List<Allele> homozygous=new ArrayList<>(2);
					homozygous.add(ctx.getReference());
					homozygous.add(ctx.getReference());
					final GenotypeBuilder gb=new GenotypeBuilder(genotype);
					gb.alleles(homozygous);
					gb.attribute(FIXED_TAG, 1);
					if(header.getFormatHeaderLine("DP")!=null)
						{
						gb.DP(depth);
						}
					
					final GenotypesContext gtx=GenotypesContext.copy(ctx.getGenotypes());
					gtx.replace(gb.make());
					
					final VariantContextBuilder vcb=new VariantContextBuilder(ctx);
					vcb.genotypes(gtx);
					w.add(vcb.make());
					countFixed++;
					}
				progress.finish();
				w.close();
				in.close();
				//closing BAMS
				for(final SamReader r:samReaders) CloserUtil.close(r);
				
				LOG.info("done sample "+sample+
						" fixed="+countFixed+
						" not-fixed="+countNonFixed+
						" total="+countTotal+
						" genotypes");
				
				//reopen in
				in = VCFUtils.createVcfIteratorFromFile(i%2==0?tmpFile1:tmpFile2);
				h2= in.getHeader();
				}
			
			final VariantContextWriter w = super.openVariantContextWriter();
			w.writeHeader(h2);
			while(in.hasNext())
				{
				w.add(in.next());
				}
			in.close();
			w.close();
			tmpFile1.delete();
			tmpFile2.delete();
			return RETURN_OK;
			}
		catch(Exception err)
			{
			return wrapException(err);
			}
		finally
			{
			CloserUtil.close(in);
			}
		}
	public static void main(String[] args) {
		new FixVcfMissingGenotypes().instanceMainWithExit(args);

	}

}
